package org.scm4j.vcs;

import static org.junit.Assert.assertEquals;
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
import java.nio.charset.IllegalCharsetNameException;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.CredentialItem;
import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.exceptions.verification.WantedButNotInvoked;
import org.scm4j.vcs.api.IVCS;
import org.scm4j.vcs.api.VCSChangeType;
import org.scm4j.vcs.api.abstracttest.VCSAbstractTest;
import org.scm4j.vcs.api.workingcopy.IVCSRepositoryWorkspace;

public class GitVCSTest extends VCSAbstractTest {

	private Repository localGitRepo;
	private ProxySelector proxySelectorBackup;
	private final RuntimeException testGitResetException = new RuntimeException("test exception on git.reset()");
	
	@Override
	public void setUp() throws Exception {
		super.setUp();
		Git git = GitVCSUtils.createRepository(new File(localVCSWorkspace.getHomeFolder(), repoName));
		localGitRepo = git.getRepository();
		proxySelectorBackup = ProxySelector.getDefault();
		ProxySelector.setDefault(null);
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
			Mockito.doReturn(mockedGit).when((GitVCS) vcs).getLocalGit(mockedLWC);
			Mockito.doThrow(testGitResetException).when(mockedGit).reset();
		} else {
			Mockito.doCallRealMethod().when((GitVCS) vcs).getLocalGit(mockedLWC);
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
		assertTrue(((GitVCS) vcs).getCredentials().get(null, u, p));
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

	private void testExceptionThrowing(Exception testException, Method m, Object[] params) throws Exception {
		Mockito.reset((GitVCS) vcs);
		Mockito.doThrow(testException).when((GitVCS) vcs).getLocalGit(mockedLWC);
		try {
			m.invoke(vcs, params);
			if (wasGetLocalGitInvoked(vcs)) {
				fail();
			}
		} catch (InvocationTargetException e) {
			if (wasGetLocalGitInvoked(vcs)) {
				assertTrue(e.getCause() instanceof RuntimeException);
				assertTrue(e.getCause().getMessage().contains(testException.getMessage()));
			}
		} catch (Exception e) {
			if (wasGetLocalGitInvoked(vcs)) {
				fail();
			}
		}
	}

	private Boolean wasGetLocalGitInvoked(IVCS vcs) throws Exception {
		try {
			Mockito.verify((GitVCS) vcs).getLocalGit(mockedLWC);
			return true;
		} catch (WantedButNotInvoked e1) {
			return false;
		}
	}

	@Test
	public void testDefaultChangeTypeToVCSType() {
		for (DiffEntry.ChangeType ct : DiffEntry.ChangeType.values()) {
			if (ct != DiffEntry.ChangeType.ADD && ct != DiffEntry.ChangeType.DELETE && ct != DiffEntry.ChangeType.MODIFY) {
				assertEquals(((GitVCS) vcs).gitChangeTypeToVCSChangeType(ct), VCSChangeType.UNKNOWN);
			}
		}
	}

	@Test
	public void testGetFileContentExceptions() {
		vcs.setFileContent(null, FILE1_NAME, LINE_1, FILE1_ADDED_COMMIT_MESSAGE);
		try {
			vcs.getFileContent(null, FILE1_NAME, "wrong encoding");
			fail();
		} catch (RuntimeException e) {
			assertTrue(e.getCause() instanceof IllegalCharsetNameException);
		} catch (Exception e) {
			fail();
		}
	}

	@Test
	public void testGitVCSUtilsCreation() {
		new GitVCSUtils();
	}

}

