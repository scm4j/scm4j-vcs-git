package org.scm4j.vcs;


import java.io.File;
import java.io.IOException;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.Mockito;
import org.scm4j.vcs.api.IVCS;
import org.scm4j.vcs.api.abstracttest.VCSAbstractTest;
import org.scm4j.vcs.api.workingcopy.IVCSRepositoryWorkspace;

public class GitVCSTest extends VCSAbstractTest {

	private Repository localGitRepo;
	private final RuntimeException testGitResetException = new RuntimeException("test exeption on git.reset()");
	
	@Override
	public void setUp() throws Exception {
		super.setUp();
		Git git = GitVCSUtils.createRepository(new File(localVCSWorkspace.getHomeFolder(), repoName));
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
		return Mockito.spy(new GitVCS(mockedVCSRepo));
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
	
	@Override 
	@Test
	@Ignore
	public void testGetCommitsRange() {
		
	}
}

