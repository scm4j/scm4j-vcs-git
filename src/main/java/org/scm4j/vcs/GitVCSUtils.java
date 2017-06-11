package org.scm4j.vcs;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.scm4j.vcs.api.exceptions.EVCSException;

import java.io.File;

public class GitVCSUtils {

	public static Git createRepository(File repoDir) {
		try {
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
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		}
	}
}
