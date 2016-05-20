package com.projectkaiser.scm.vcs;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.UUID;
import java.util.function.Consumer;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import com.projectkaiser.scm.vcs.api.IVCS;
import com.projectkaiser.scm.vcs.api.VCSWorkspace;
import com.projectkaiser.scm.vcs.api.exceptions.EVCSBranchExists;

public class GitVCSTest  {
	
	private static final String FILE1_ADDED_COMMIT_MESSAGE = "file1 added";
	private static final String FILE2_ADDED_COMMIT_MESSAGE = "file2 added";
	private static final String WORKSPACE_DIR = System.getProperty("java.io.tmpdir") + "pk-vcs-workspaces";
	private static final String REPO_NAME = "pk-vcs-git-testrepo";
	private static final String GITHUB_USER = System.getProperty("PK_VCS_TEST_GITHUB_USER") == null ? 
			System.getenv("PK_VCS_TEST_GITHUB_USER") : System.getProperty("PK_VCS_TEST_GITHUB_USER");
	private static final String GITHUB_PASS = System.getProperty("PK_VCS_TEST_GITHUB_PASS") == null ? 
			System.getenv("PK_VCS_TEST_GITHUB_PASS") : System.getProperty("PK_VCS_TEST_GITHUB_PASS");
	private static final String NEW_BRANCH = "new-branch";
	private static final String SRC_BRANCH = "master";
	private static final String INITIAL_COMMIT_MESSAGE = "Initial commit";
	private static final String CREATED_DST_BRANCH_COMMIT_MESSAGE = "created dst branch";
	private static final String CONTENT_CHANGED_COMMIT_MESSAGE = "changed file content";
	private static final String MERGE_COMMIT_MESSAGE = "merged.";
	private static final String DELETE_BRANCH_COMMIT_MESSAGE = "deleted";
	private static final String PROXY_HOST = getJvmProperty("https.proxyHost");
	private static final Integer PROXY_PORT = getJvmProperty("https.proxyPort") == null ? null :
			Integer.parseInt(getJvmProperty("https.proxyPort"));
	private static final String PROXY_USER = getJvmProperty("https.proxyUser");
	private static final String PROXY_PASS = getJvmProperty("https.proxyPassword");
	private static final String LINE_1 = "line 1";
	private static final String LINE_2 = "line 2";
	
	private IVCS vcs;
	private GitHub github;
	private GitVCS gitVCS;
	private String repoName;
	private GHRepository repo;
	private String gitUrl = "https://github.com/" + GITHUB_USER + "/";
	
	
	private Integer counter = 0;
	
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
	
	@Before
	public void setUp() throws IOException {
		github = GitHub.connectUsingPassword(GITHUB_USER, GITHUB_PASS);
		String uuid = UUID.randomUUID().toString();
		repoName = (REPO_NAME + "_" + uuid);
		 
		repo = github.createRepository(repoName)
				.issues(false)
				.wiki(false)
				.autoInit(true)
				.downloads(false)
				.create();
		gitVCS = new GitVCS(null, WORKSPACE_DIR, gitUrl + repoName + ".git");
		vcs = gitVCS;
		vcs.setCredentials(GITHUB_USER, GITHUB_PASS);
		if (PROXY_HOST != null) {
			vcs.setProxy(PROXY_HOST, PROXY_PORT, PROXY_USER, PROXY_PASS);
		}
	}
	
	@After
	public void tearDown() {
		try {
			repo.delete();
		} catch (IOException e) {
			// do not affect the test
		}
	}
	
	@Test
	public void testGitCreateAndDeleteBranch() throws InterruptedException, IOException {
		vcs.createBranch(SRC_BRANCH, NEW_BRANCH, CREATED_DST_BRANCH_COMMIT_MESSAGE);
		Thread.sleep(2000); // next operation fails time to time. Looks like github has some latency on branch operations
		assertTrue(repo.getBranches().containsKey(NEW_BRANCH));
		assertTrue(repo.getBranches().size() == 2); // master & NEW_BRANCH
		
		try {
			vcs.createBranch(SRC_BRANCH, NEW_BRANCH, CREATED_DST_BRANCH_COMMIT_MESSAGE);
			fail("\"Branch exists\" situation not detected");
		} catch (EVCSBranchExists e) {
		}
		
		vcs.deleteBranch(NEW_BRANCH, DELETE_BRANCH_COMMIT_MESSAGE);
		Thread.sleep(2000); // next operation fails from time to time. Looks like github has some latency on branch operations
		assertTrue (repo.getBranches().size() == 1);
	}
	
	@Test
	public void testGetSetFileContent() throws NoFilepatternException, GitAPIException, IOException {
		VCSWorkspace w = VCSWorkspace.getLockedWorkspace(gitVCS.getRepoFolder());
		try {
			try (Git git = gitVCS.getLocalGit(w)) {
				File file = new File(w.getFolder(), "folder/file1.txt");
				file.getParentFile().mkdirs();
				file.createNewFile();
				PrintWriter out = new PrintWriter(file);
				out.println(LINE_1);
				out.close();

				git
						.add()
						.addFilepattern("folder/file1.txt")
						.call();
				
				git
						.commit()
						.setMessage(FILE1_ADDED_COMMIT_MESSAGE)
						.call();
				
				RefSpec spec = new RefSpec("master:master");
				git
						.push()
						.setRefSpecs(spec)
						.setForce(true)
						.setRemote("origin")
						.setCredentialsProvider(gitVCS.getCredentials())
						.call();
				
				vcs.setFileContent("master", "folder/file1.txt", LINE_2, CONTENT_CHANGED_COMMIT_MESSAGE);
				
				assertEquals(vcs.getFileContent("master", "folder/file1.txt"), LINE_2);
				assertEquals(vcs.getFileContent("master", "folder/file1.txt", "UTF-8"), LINE_2);
				
			}
		} finally {
			w.unlock();
		}
	}
	
	@Test
	public void testGitMerge() throws IOException, NoFilepatternException, GitAPIException, InterruptedException {
		vcs.createBranch(SRC_BRANCH, NEW_BRANCH, CREATED_DST_BRANCH_COMMIT_MESSAGE);
		VCSWorkspace w = VCSWorkspace.getLockedWorkspace(gitVCS.getRepoFolder());
		try {
			try (Git git = gitVCS.getLocalGit(w)) {
				
				git
						.checkout()
						.setCreateBranch(true)
						.setStartPoint("refs/remotes/origin/" + SRC_BRANCH)
						.setName(SRC_BRANCH)
						.call(); // switch to master
				
				File file1 = new File(w.getFolder(), "file1.txt");
				file1.createNewFile();

				git
						.add()
						.addFilepattern("file1.txt")
						.call();
				
				git
						.commit()
						.setMessage(FILE1_ADDED_COMMIT_MESSAGE)
						.call();
				
				git
				
						.checkout()
						.setCreateBranch(false)
						.setName(NEW_BRANCH) 
						.call(); // switch to new-branch
				
				File file2 = new File(w.getFolder(), "file2.txt");
				file2.createNewFile();
				
				git
						.add()
						.addFilepattern("file2.txt")
						.call();
				
				git
						.commit()
						.setMessage(FILE2_ADDED_COMMIT_MESSAGE)
						.call();
				
				git
						.push()
						.setPushAll()
						.setRemote("origin")
						.setCredentialsProvider(gitVCS.getCredentials())
						.call();
				
				git
						.checkout()
						.setCreateBranch(false)
						.setForce(false)
						.setName(SRC_BRANCH)
						.call(); // switch to master
				
				vcs.merge(NEW_BRANCH, SRC_BRANCH, MERGE_COMMIT_MESSAGE);
				
				
				git
						.pull()
						.setCredentialsProvider(gitVCS.getCredentials())
						.call();
				
				assertTrue(file1.exists());
				assertTrue(file2.exists());
				
				Iterable<RevCommit> commits = git
						.log()
						.all()
						.call();
				
				commits.forEach(new Consumer<RevCommit>() {
	
					@Override
					public void accept(RevCommit arg0) {
						if (!arg0.getFullMessage().equals(INITIAL_COMMIT_MESSAGE) &&
								!arg0.getFullMessage().equals(FILE2_ADDED_COMMIT_MESSAGE) &&
								!arg0.getFullMessage().equals(FILE1_ADDED_COMMIT_MESSAGE) &&
								!arg0.getFullMessage().equals(MERGE_COMMIT_MESSAGE)) {
							fail("Unexpected commit met: " + arg0.getFullMessage());
						} else {
							counter++;
						}
					}
				});
				
				assertTrue(counter == 4);
				
			}
		} finally {
			w.unlock();
		}
	}
}

