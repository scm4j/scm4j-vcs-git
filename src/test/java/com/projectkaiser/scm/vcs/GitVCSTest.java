package com.projectkaiser.scm.vcs;


import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.junit.After;
import org.junit.BeforeClass;
import org.mockito.Mockito;

import com.projectkaiser.scm.vcs.api.IVCS;
import com.projectkaiser.scm.vcs.api.abstracttest.VCSAbstractTest;
import com.projectkaiser.scm.vcs.api.workingcopy.IVCSRepositoryWorkspace;

public class GitVCSTest extends VCSAbstractTest {

	private Repository localGitRepo;
	private RuntimeException testGitResetException = new RuntimeException("test exeption on git.reset()");
	
	@Override
	public void setUp() throws Exception {
		super.setUp();
		Git git = Git
				.init()
				.setDirectory(new File(localVCSWorkspace.getHomeFolder(), repoName))
				.setBare(false)
				.call();
		localGitRepo = git.getRepository();
		git
				.commit()
				.setMessage("Initial commit")
				.call();
	}
	
	@After
	public void tearDown() throws IOException {
		localGitRepo.close();
	    FileUtils.deleteDirectory(localGitRepo.getDirectory());
	}
	
	@Override
	protected String getTestRepoUrl() {
		return ("file:///" + localVCSWorkspace.getHomeFolder() + "/").replace("\\", "/");
	}

	@Override
	protected IVCS getVCS(IVCSRepositoryWorkspace mockedVCSRepo) {
		IVCS vcs = Mockito.spy(new GitVCS(mockedVCSRepo));
		return vcs;
	}

	@Override
	protected void setMakeFailureOnVCSReset(Boolean doMakeFailure) {
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
}

