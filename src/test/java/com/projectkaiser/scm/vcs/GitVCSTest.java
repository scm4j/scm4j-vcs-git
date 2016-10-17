package com.projectkaiser.scm.vcs;


import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.eclipse.jgit.api.Git;
import org.junit.After;
import org.junit.BeforeClass;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.mockito.Mockito;

import com.projectkaiser.scm.vcs.api.IVCS;
import com.projectkaiser.scm.vcs.api.abstracttest.VCSAbstractTest;
import com.projectkaiser.scm.vcs.api.workingcopy.IVCSRepositoryWorkspace;

public class GitVCSTest extends VCSAbstractTest {
	
	private static final String GITHUB_USER = System.getProperty("PK_VCS_TEST_GITHUB_USER") == null ? 
			System.getenv("PK_VCS_TEST_GITHUB_USER") : System.getProperty("PK_VCS_TEST_GITHUB_USER");
	private static final String GITHUB_PASS = System.getProperty("PK_VCS_TEST_GITHUB_PASS") == null ? 
			System.getenv("PK_VCS_TEST_GITHUB_PASS") : System.getProperty("PK_VCS_TEST_GITHUB_PASS");
	private static final String PROXY_HOST = getJvmProperty("https.proxyHost");
	private static final Integer PROXY_PORT = getJvmProperty("https.proxyPort") == null ? null :
			Integer.parseInt(getJvmProperty("https.proxyPort"));
	private static final String PROXY_USER = getJvmProperty("https.proxyUser");
	private static final String PROXY_PASS = getJvmProperty("https.proxyPassword");
	
	private GitHub github;
	
	private GHRepository gitHubRepo;
	private String gitUrl = "https://github.com/" + GITHUB_USER + "/";
	private Git mockedGit;
	private RuntimeException testGitResetException = new RuntimeException("test exeption on git.reset()");
	
	@BeforeClass
	public static void setUpClass() {
		assertTrue("Set PK_VCS_TEST_GITHUB_USER enviroment variable as user name of a valid github account to execute tests.", 
				GITHUB_USER != null);
		assertTrue("Set PK_VCS_TEST_GITHUB_PASS enviroment variable as user password of a valid github account to execute tests.", 
				GITHUB_PASS != null);
	}
	
	private static String getJvmProperty(String name) {
		if (name == null) {
			return null;
		}
		
		String res = System.getProperty(name);
		if (res != null) {
			return res;
		}
		
		res = System.getenv("JAVA_OPTS");
		if (res == null) {
			return null;
		}
		
		Integer st = res.indexOf(name);
		if (st < 0) {
			return null;
		}
		
		res = res.substring(st + name.length() + 1, res.indexOf(" -D", st + name.length() + 1) < 0 ? name.length() : 
				res.indexOf(" -D", st + name.length())).trim();
		return res;
	}
	
	
	@Override
	public void setUp() throws Exception {
		super.setUp();
		github = GitHub.connectUsingPassword(GITHUB_USER, GITHUB_PASS);
		gitHubRepo = github.createRepository(repoName)
				.issues(false)
				.wiki(false)
				.autoInit(true)
				.downloads(false)
				.create();
	}
	
	@After
	public void tearDown() {
		try {
			gitHubRepo.delete();
		} catch (IOException e) {
			// do not affect the test
		}
	}
	
	@Override
	protected String getTestRepoUrl() {
		return gitUrl;
	}

	@Override
	protected IVCS getVCS(IVCSRepositoryWorkspace mockedVCSRepo) {
		IVCS vcs = Mockito.spy(new GitVCS(mockedVCSRepo));
		vcs.setCredentials(GITHUB_USER, GITHUB_PASS);
		if (PROXY_HOST != null) {
			vcs.setProxy(PROXY_HOST, PROXY_PORT, PROXY_USER, PROXY_PASS);
		}
		return vcs;
	}

	@Override
	protected void setMakeFailureOnVCSReset(Boolean doMakeFailure) {
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
}

