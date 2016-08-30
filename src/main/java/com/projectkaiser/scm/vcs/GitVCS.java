package com.projectkaiser.scm.vcs;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import com.projectkaiser.scm.vcs.api.IVCS;
import com.projectkaiser.scm.vcs.api.PKVCSMergeResult;
import com.projectkaiser.scm.vcs.api.exceptions.EVCSBranchExists;
import com.projectkaiser.scm.vcs.api.exceptions.EVCSException;
import com.projectkaiser.scm.vcs.api.exceptions.EVCSFileNotFound;
import com.projectkaiser.scm.vcs.api.workingcopy.IVCSLockedWorkingCopy;
import com.projectkaiser.scm.vcs.api.workingcopy.IVCSRepositoryWorkspace;

public class GitVCS implements IVCS {

	private CredentialsProvider credentials;
	
	IVCSRepositoryWorkspace repo;
	
	public CredentialsProvider getCredentials() {
		return credentials;
	}
	
	public GitVCS(IVCSRepositoryWorkspace repo) {
		this.repo = repo;
	}
	
	public void setCredentials(CredentialsProvider credentials) {
		this.credentials = credentials;
	}

	@Override
	public void createBranch(String srcBranchName, String newBranchName, String commitMessage) {
		// note: no commit message could be attached in Git
		try {
			try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy()) {
				try (Git git = getLocalGit(wc)) {
					
					git
							.checkout()
							.setCreateBranch(true)
							.setStartPoint("origin/" + srcBranchName)
							.setName(srcBranchName)
							.call(); // switch to master
					
					git
							.branchCreate()
							.setName(newBranchName)
							.call();
					
					RefSpec refSpec = new RefSpec().setSourceDestination(newBranchName, 
							newBranchName); 
					git
							.push()
							.setRefSpecs(refSpec)
							.setCredentialsProvider(credentials)
							.call();
					git.getRepository().close();
				}
			}
		} catch (RefAlreadyExistsException e) {
			throw new EVCSBranchExists (e);
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void deleteBranch(String branchName, String commitMessage) {
		try {
			try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy()) {
				try (Git git = getLocalGit(wc)) {
					
					git
							.pull()
							.setCredentialsProvider(credentials)
							.call();
					
					git
							.branchDelete()
							.setBranchNames(branchName)
							.call();

					git
							.commit()
							.setMessage(commitMessage)
							.setAll(true)
							.call();
					
					RefSpec refSpec = new RefSpec( ":refs/heads/" + branchName);
					
					git
							.push()
							.setPushAll()
							.setRefSpecs(refSpec)
							.setRemote("origin")
							.setCredentialsProvider(credentials)
							.call();
					git.getRepository().close();
				}
			}
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public Git getLocalGit(IVCSLockedWorkingCopy wc) {
		
		Repository gitRepo;
		try {
			gitRepo = new FileRepositoryBuilder()
					.setGitDir(new File(wc.getFolder(), ".git"))
					.build();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		Boolean repoInited = gitRepo
				.getObjectDatabase()
				.exists();
		if (!repoInited) {
			try {
				Git
						.cloneRepository()
						.setDirectory(wc.getFolder())
						.setURI(repo.getRepoUrl())
						.setCredentialsProvider(credentials)
						.setNoCheckout(true)
						.call()
						.close();
			} catch (GitAPIException e) {
				throw new EVCSException(e);
			}
			
		}
		Git res = new Git(gitRepo);
		return res; 
	}

	@Override
	public PKVCSMergeResult merge(String sourceBranchUrl, String destBranchUrl, String commitMessage) {
		try {
			try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy()) {
				try (Git git = getLocalGit(wc)) {
					
					git
							.pull()
							.setCredentialsProvider(credentials)
							.call();
				
					git
							.checkout()
							.setCreateBranch(false)
							.setName(destBranchUrl)
							.call(); 
			
					MergeResult mr = git
							.merge()
							.include(git.getRepository().findRef("origin/" + sourceBranchUrl))
							.setMessage(commitMessage)
							.call(); // actually do the merge
			
			
					PKVCSMergeResult res = new PKVCSMergeResult();
					
					res.setSuccess(!mr.getMergeStatus().equals(MergeResult.MergeStatus.CONFLICTING) &&
							!mr.getMergeStatus().equals(MergeResult.MergeStatus.FAILED) && 
							!mr.getMergeStatus().equals(MergeResult.MergeStatus.ABORTED) &&
							!mr.getMergeStatus().equals(MergeResult.MergeStatus.NOT_SUPPORTED));
					
					
					if (!res.getSuccess()) {
						res.getConflictingFiles().addAll(mr.getConflicts().keySet());
						try {
							git
									.reset()
									.setMode(ResetType.HARD)
									.call();
						} catch(Exception e) {
							wc.setCorrupted(true);
						}
					} else {
						git
								.push()
								.setPushAll()
								.setRemote("origin")
								.setCredentialsProvider(credentials)
								.call();
					}
					git.getRepository().close();
					return res;
				}
			}
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void setCredentials(String user, String password) {
		credentials = new UsernamePasswordCredentialsProvider(user, password);
	}

	@Override
	public void setProxy(final String host, final int port, String proxyUser, String proxyPassword) {
		ProxySelector.setDefault(new ProxySelector() {
			
			final ProxySelector delegate = ProxySelector.getDefault();
			
			@Override
			public List<Proxy> select(URI uri) {
				if (uri.toString().toLowerCase().contains(repo.getRepoUrl().toLowerCase())) {
					return Arrays.asList(new Proxy(Type.HTTP, InetSocketAddress
		                    .createUnresolved(host, port)));
				} else {
					return delegate == null ? Arrays.asList(Proxy.NO_PROXY)
			                : delegate.select(uri);
				}
			}
			
			@Override
			public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
				if (uri.toString().contains(repo.getRepoUrl())) {
					throw new RuntimeException("GitVCS proxy connect failed");
				}
			}
		});
	}

	@Override
	public String getRepoUrl() {
		return repo.getRepoUrl(); 
	}

	@Override
	public String getFileContent(String branchName, String fileRelativePath, String encoding) {
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy()) {
			try (Git git = getLocalGit(wc)) {
				
				git
						.pull()
						.setCredentialsProvider(credentials)
						.call();
				
				git
						.checkout()
						.setCreateBranch(false)
						.addPath(fileRelativePath)
						.setName(branchName)
						.call();
				git.getRepository().close();
				
				File file = new File(wc.getFolder(), fileRelativePath);
				
				try {
					return IOUtils.toString(file.toURI(), encoding);
				} catch (IOException e) {
					throw new EVCSFileNotFound(String.format("File %s is not found", fileRelativePath));
				}
			}
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (EVCSFileNotFound e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void setFileContent(String branchName, String filePath, String content, String commitMessage) {
		try {
			try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy()) {
				try (Git git = getLocalGit(wc)) {
					
					git
							.pull()
							.setCredentialsProvider(credentials)
							.call();
			
					git
							.checkout()
							.setCreateBranch(false)
							.addPath(filePath)
							.setName(branchName)
							.call();
					
					File file = new File(wc.getFolder(), filePath);
					FileWriter fw = new FileWriter(file, false);
					fw.write(content);
					fw.close();
					
					git
							.commit()
							.setOnly(filePath)
							.setMessage(commitMessage)
							.call();
					
					RefSpec refSpec = new RefSpec(branchName + ":" + branchName);
					
					git
							.push()
							.setRefSpecs(refSpec)
							.setRemote("origin")
							.setCredentialsProvider(credentials)
							.call();
					git.getRepository().close();
				}
			}
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public String getFileContent(String branchName, String filePath) {
		return getFileContent(branchName, filePath, StandardCharsets.UTF_8.name());
	}

}
