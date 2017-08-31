package org.scm4j.vcs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Authenticator;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.exceptions.verification.WantedButNotInvoked;
import org.scm4j.vcs.api.IVCS;
import org.scm4j.vcs.api.VCSChangeType;
import org.scm4j.vcs.api.VCSTag;
import org.scm4j.vcs.api.abstracttest.VCSAbstractTest;
import org.scm4j.vcs.api.exceptions.EVCSException;
import org.scm4j.vcs.api.workingcopy.IVCSLockedWorkingCopy;
import org.scm4j.vcs.api.workingcopy.IVCSRepositoryWorkspace;

public class GitVCSTest extends VCSAbstractTest {

	private Repository localGitRepo;
	private ProxySelector proxySelectorBackup;
	private final RuntimeException testGitResetException = new RuntimeException("test exception on git.reset()");
	private GitVCS git;
	
	@Override
	public void setUp() throws Exception {
		super.setUp();
		Git fileGitRepo = GitVCSUtils.createRepository(new File(localVCSWorkspace.getHomeFolder(), repoName));
		localGitRepo = fileGitRepo.getRepository();
		proxySelectorBackup = ProxySelector.getDefault();
		ProxySelector.setDefault(null);
		git = (GitVCS) vcs;
	}
	
	@After
	public void tearDown() throws IOException {
		localGitRepo.close();
	    FileUtils.deleteDirectory(localGitRepo.getDirectory());
		ProxySelector.setDefault(proxySelectorBackup);
	}
	
	@Override
	protected String getTestRepoUrl() {
		return ("file:///" + localVCSWorkspace.getHomeFolder() + "/").replace("\\", "/");
	}

	@Override
	protected IVCS getVCS(IVCSRepositoryWorkspace mockedVCSRepo) {
		return Mockito.spy(new GitVCS(mockedVCSRepo));
	}

	@Override
	protected void setMakeFailureOnVCSReset(Boolean doMakeFailure) throws Exception {
		Git mockedGit;
		if (doMakeFailure) {
			mockedGit = Mockito.spy(((GitVCS) vcs).getLocalGit(mockedLWC));
			Mockito.doReturn(mockedGit).when(((GitVCS) vcs)).getLocalGit(mockedLWC);
			Mockito.doThrow(testGitResetException).when(mockedGit).reset();
		} else {
			Mockito.doCallRealMethod().when(((GitVCS) vcs)).getLocalGit(mockedLWC);
			mockedGit = null;
		}
	}

	@Override
	protected String getVCSTypeString() {
		return GitVCS.GIT_VCS_TYPE_STRING;
	}

	@Test
	public void testSetCredentials() {
		vcs.setCredentials("user", "password");
		CredentialItem.Username u = new CredentialItem.Username();
		CredentialItem.Password p = new CredentialItem.Password();
		assertTrue(git.getCredentials().get(null, u, p));
		assertEquals(u.getValue(), "user");
		assertEquals(new String(p.getValue()), "password");
	}

	@Test
	public void testProxyAuth() throws Exception {
		PasswordAuthentication initialAuth = Authenticator.requestPasswordAuthentication(InetAddress.getByName("localhost"),
				123, "http", "", "");
		IVCS vcs = new GitVCS(localVCSWorkspace.getVCSRepositoryWorkspace("localhost"));
		vcs.setProxy("localhost", 123, "username", "pwd");

		PasswordAuthentication resultAuth = Authenticator.requestPasswordAuthentication(
				InetAddress.getByName("localhost"), 123, "http", "", "");
		assertEquals(resultAuth.getUserName(), "username");
		assertEquals(new String(resultAuth.getPassword()), "pwd");

		resultAuth = Authenticator.requestPasswordAuthentication(
				InetAddress.getByName("localhost"), 124, "http", "", "");
		assertEquals(resultAuth, initialAuth);
	}

	@Test
	public void testProxySelector() throws URISyntaxException {
		vcs.setProxy("localhost", 123, "username", "pwd");
		ProxySelector actualPS = ProxySelector.getDefault();
		List<Proxy> proxies = actualPS.select(new URI(vcs.getRepoUrl()));
		assertTrue(proxies.size() == 1);
		Proxy actualP = proxies.get(0);
		assertTrue(actualP.address() instanceof InetSocketAddress);
		InetSocketAddress isa = (InetSocketAddress) actualP.address();
		assertEquals(isa.getHostName(), "localhost");
		assertEquals(isa.getPort(), 123);
	}

	@Test
	public void testParentProxySelectorUsage() throws URISyntaxException {
		ProxySelector mockedPS = Mockito.mock(ProxySelector.class);
		ProxySelector.setDefault(mockedPS);
		vcs.setProxy("localhost", 123, "username", "pwd");
		ProxySelector actualPS = ProxySelector.getDefault();
		URI uri = new URI("http://unknown");
		actualPS.select(uri);
		Mockito.verify(mockedPS).select(uri);
	}

	@Test
	public void testNullProxySelector() throws URISyntaxException {
		ProxySelector.setDefault(null);
		vcs.setProxy("localhost", 123, "username", "pwd");
		ProxySelector actualPS = ProxySelector.getDefault();
		List<Proxy> proxies = actualPS.select(new URI("http://unknown"));
		assertTrue(proxies.size() == 1);
		assertEquals(proxies.get(0), Proxy.NO_PROXY);
	}

	@Test
	public void testParentSelectorCallOnConnectFailed() throws URISyntaxException {
		ProxySelector mockedPS = Mockito.mock(ProxySelector.class);
		ProxySelector.setDefault(mockedPS);
		vcs.setProxy("localhost", 123, "username", "pwd");
		ProxySelector actualPS = ProxySelector.getDefault();
		URI testURI = new URI("http://proxy.net");
		SocketAddress testSA = InetSocketAddress.createUnresolved("http://proxy.net", 123);
		IOException testException = new IOException("test exception");
		actualPS.connectFailed(testURI, testSA, testException);
		Mockito.verify(mockedPS).connectFailed(testURI, testSA, testException);
	}
	@Test
	public void testNoParentSelectorOnConnectFailed() throws URISyntaxException {
		ProxySelector.setDefault(null);
		vcs.setProxy("localhost", 123, "username", "pwd");
		ProxySelector actualPS = Mockito.spy(ProxySelector.getDefault());
		URI testURI = new URI("http://proxy.net");
		SocketAddress testSA = InetSocketAddress.createUnresolved("http://proxy.net", 123);
		IOException testException = new IOException("test exception");
		actualPS.connectFailed(testURI, testSA, testException);
		Mockito.verify(actualPS).connectFailed(testURI, testSA, testException);
		Mockito.verifyNoMoreInteractions(actualPS);
	}

	@Test
	public void testVCSTypeString() {
		assertEquals(vcs.getVCSTypeString(), GitVCS.GIT_VCS_TYPE_STRING);
	}

	@Test
	public void testExceptions() throws Exception {
		@SuppressWarnings("serial")
		GitAPIException eApi = new GitAPIException("test git exception") {};
		Exception eCommon = new Exception("test common exception");
		for (Method m : IVCS.class.getDeclaredMethods()) {
			Object[] params = new Object[m.getParameterTypes().length];
			Integer i = 0;
			for (Class<?> clazz : m.getParameterTypes()) {
				params[i] = clazz.isPrimitive() ? 0: null;
				i++;
			}
			testExceptionThrowing(eApi, m, params);

			testExceptionThrowing(eCommon, m, params);
		}
	}
	
	private void testExceptionThrowingNoMock(Exception testException, Method m, Object[] params) throws Exception {
		try {
			m.invoke(vcs, params);
			if (!m.getName().equals("checkout") && wasGetLocalGitInvoked()) {
				fail();
			}
		} catch (InvocationTargetException e) {
			if (!m.getName().equals("checkout") && wasGetLocalGitInvoked()) {
				// InvocationTargetException <- EVCSException <- GitAPIException 
				assertTrue(e.getCause().getCause().getClass().isAssignableFrom(testException.getClass()));
				assertTrue(e.getCause().getMessage().contains(testException.getMessage()));
			}
		} catch (Exception e) {
			if (!m.getName().equals("checkout") && wasGetLocalGitInvoked()) {
				fail();
			}
		}
		
	}

	private void testExceptionThrowing(Exception testException, Method m, Object[] params) throws Exception {
		Mockito.reset(git);
		Mockito.doThrow(testException).when(git).getLocalGit(mockedLWC);
		testExceptionThrowingNoMock(testException, m, params);
		
	}

	private Boolean wasGetLocalGitInvoked() throws Exception {
		try {
			Mockito.verify(git).getLocalGit(mockedLWC);
			return true;
		} catch (WantedButNotInvoked e1) {
			return false;
		}
	}

	@Test
	public void testDefaultChangeTypeToVCSType() {
		for (DiffEntry.ChangeType ct : DiffEntry.ChangeType.values()) {
			if (ct != DiffEntry.ChangeType.ADD && ct != DiffEntry.ChangeType.DELETE && ct != DiffEntry.ChangeType.MODIFY) {
				assertEquals(git.gitChangeTypeToVCSChangeType(ct), VCSChangeType.UNKNOWN);
			}
		}
	}

	@Test
	public void testGitVCSUtilsCreation() {
		assertNotNull(new GitVCSUtils());
	}
	
	@Test
	public void testGetTagsUnannotated() throws Exception {
		createUnannotatedTag(null, TAG_NAME_1, null);
		List<VCSTag> tags = vcs.getTags();
		assertTrue(tags.size() == 1);
		VCSTag tag = tags.get(0);
		assertNull(tag.getAuthor());
		assertNull(tag.getTagMessage());
		assertEquals(tag.getTagName(), TAG_NAME_1);
		assertEquals(tag.getRelatedCommit(), vcs.getHeadCommit(null));
	}
	
	public VCSTag createUnannotatedTag(String branchName, String tagName, String revisionToTag) throws Exception {
		try (IVCSLockedWorkingCopy wc = localVCSRepo.getVCSLockedWorkingCopy();
			 Git localGit = git.getLocalGit(wc);
			 Repository gitRepo = localGit.getRepository();
			 RevWalk rw = new RevWalk(gitRepo)) {
			
			git.checkout(localGit, gitRepo, branchName, null);
			
			RevCommit commitToTag = revisionToTag == null ? null : rw.parseCommit(ObjectId.fromString(revisionToTag));
			
			Ref ref = localGit
					.tag()
					.setAnnotated(false)
					.setName(tagName)
					.setObjectId(commitToTag)
					.call();
			
			localGit
					.push()
					.setPushAll()
					.setRefSpecs(new RefSpec(ref.getName()))
					.setRemote("origin")
					.setCredentialsProvider(git.getCredentials())
					.call();
			return new VCSTag(tagName, null, null, revisionToTag == null ? vcs.getHeadCommit(branchName)
					: git.getVCSCommit(commitToTag));
		}
	}
	
	@Test
	public void testCheckoutExceptions() throws Exception {
		@SuppressWarnings("serial")
		GitAPIException eApi = new GitAPIException("test git exception") {};
		Exception eCommon = new Exception("test common exception");
		Mockito.doThrow(eCommon).when(git).getLocalGit((String) null);
		try {
			git.checkout(null, null, null);
			fail();
		} catch (RuntimeException e) {
			assertTrue(e.getCause().getClass().isAssignableFrom(eCommon.getClass()));
			assertTrue(e.getCause().getMessage().contains(eCommon.getMessage()));
		}
		
		Mockito.doThrow(eApi).when(git).getLocalGit((String) null);
		try {
			git.checkout(null, null, null);
			fail();
		} catch (EVCSException e) {
			assertTrue(e.getCause().getClass().isAssignableFrom(eApi.getClass()));
			assertTrue(e.getCause().getMessage().contains(eApi.getMessage()));
		}
	}
}

