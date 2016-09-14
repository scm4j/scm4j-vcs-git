package com.projectkaiser.scm.vcs;


import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRefNameException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.After;
import org.junit.BeforeClass;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.mockito.Mockito;

import com.projectkaiser.scm.vcs.api.IVCS;
import com.projectkaiser.scm.vcs.api.abstracttest.VCSAbstractTest;
import com.projectkaiser.scm.vcs.api.workingcopy.IVCSLockedWorkingCopy;
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
	private static final String VCS_TYPE_STRING = "git";
	
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
	protected void sendFile(IVCSLockedWorkingCopy wc, String branchName, String filePath, String commitMessage) throws Exception {
		try (Git git = ((GitVCS) vcs).getLocalGit(wc)) {
			checkout(branchName, git);
			
			git
					.add()
					.addFilepattern(filePath)
					.call();
			
			git
					.commit()
					.setMessage(commitMessage)
					.call();
			
			RefSpec spec = new RefSpec(branchName + ":" + branchName);
			git
					.push()
					.setRefSpecs(spec)
					.setRemote("origin")
					.setCredentialsProvider(((GitVCS) vcs).getCredentials())
					.call();
	
			git.getRepository().close();
		}
	}
	
	@Override
	protected void checkout(String branchName, IVCSLockedWorkingCopy wc) throws Exception {
		try (Git git = ((GitVCS) vcs).getLocalGit(wc)) {
			checkout(branchName, git);
			git.getRepository().close();
		}
	}

	private void checkout(String branchName, Git git) throws IOException, GitAPIException, RefAlreadyExistsException,
			RefNotFoundException, InvalidRefNameException, CheckoutConflictException {
		Boolean branchExists = git.getRepository().exactRef("refs/heads/" + branchName) != null;
		
		git
				.checkout()
				.setCreateBranch(!branchExists)
				.setStartPoint("origin/" + branchName)
				.setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
				.setName(branchName)
				.call(); // switch to branchName and track origin/branchName
	}
	
	@Override
	public String getVCSTypeString() {
		return VCS_TYPE_STRING;
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
	protected Set<String> getBranches() throws IOException {
		return gitHubRepo.getBranches().keySet();
	}

	@Override
	protected Set<String> getCommitMessagesRemote(String branchName) throws Exception {
		try (IVCSLockedWorkingCopy wc = localVCSRepo.getVCSLockedWorkingCopy()) {
			try (Git git = ((GitVCS) vcs).getLocalGit(wc)) {
				Iterable<RevCommit> logs = git
						.log()
						.add(git.getRepository().resolve("remotes/origin/" + branchName))
						.call();
				Set<String> res = new HashSet<>();
				for (RevCommit commit : logs) {
					res.add(commit.getFullMessage());
				}
				git.getRepository().close();
				return res;
			}
		}
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
}

