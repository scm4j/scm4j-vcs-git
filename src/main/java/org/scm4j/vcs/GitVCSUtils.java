package org.scm4j.vcs;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.File;

public class GitVCSUtils {

	public static Git createRepository(File repoDir) throws GitAPIException {
		Git git = Git
				.init()
				.setDirectory(repoDir)
				.setBare(false)
				.call();
		git
				.commit()
				.setMessage("Initial commit")
				.call();
		return git;
	}
}
