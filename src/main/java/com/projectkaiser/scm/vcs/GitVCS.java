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
import org.apache.commons.logging.Log;
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

import com.projectkaiser.scm.vcs.api.AbstractVCS;
import com.projectkaiser.scm.vcs.api.IVCS;
import com.projectkaiser.scm.vcs.api.PKVCSMergeResult;
import com.projectkaiser.scm.vcs.api.VCSWorkspace;
import com.projectkaiser.scm.vcs.api.exceptions.EVCSBranchExists;
import com.projectkaiser.scm.vcs.api.exceptions.EVCSException;
import com.projectkaiser.scm.vcs.api.exceptions.EVCSFileNotFound;

public class GitVCS extends AbstractVCS implements IVCS {

	private CredentialsProvider credentials;
	
	public GitVCS(Log logger, String workspacePath, String remoteUrl) {
		super (logger, workspacePath, remoteUrl);
	}
	
	public CredentialsProvider getCredentials() {
		return credentials;
	}

	public void setCredentials(CredentialsProvider credentials) {
		this.credentials = credentials;
	}

	@Override
	public void createBranch(String srcBranchName, String newBranchName, String commitMessage) {
		// note: no commit message could be attached in Git
		try {
			VCSWorkspace workspace = VCSWorkspace.getLockedWorkspace(repoFolder);
			try {
				
				try (Git git = getLocalGit(workspace)) {
					
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
					
					git
							.branchDelete()
							.setBranchNames(newBranchName)
							.call();
					
				}
			} finally {
				workspace.unlock();
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
			VCSWorkspace workspace = VCSWorkspace.getLockedWorkspace(repoFolder);
			try {
				try (Git git = getLocalGit(workspace)) {
					
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
					
				}
							
			} finally {
				workspace.unlock();
			}
			
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public Git getLocalGit(VCSWorkspace workspace) {
		
		Repository repo;
		try {
			repo = new FileRepositoryBuilder()
					.setGitDir(new File(workspace.getFolder(), ".git"))
					.build();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		Boolean repoInited = repo
				.getObjectDatabase()
				.exists();
		if (!repoInited) {
			try {
				Git
						.cloneRepository()
						.setDirectory(workspace.getFolder())
						.setURI(baseUrl)
						.setCredentialsProvider(credentials)
						.setNoCheckout(true)
						.call();
			} catch (GitAPIException e) {
				throw new EVCSException(e);
			}
			
		}
		
		return new Git(repo);
	}

	@Override
	public PKVCSMergeResult merge(String sourceBranchUrl, String destBranchUrl, String commitMessage) {
		
		try {
			VCSWorkspace workspace = VCSWorkspace.getLockedWorkspace(repoFolder);
			try {
				try (Git git = getLocalGit(workspace)) {
					
					git
							.pull()
							.setCredentialsProvider(credentials)
							.call();
				
					checkout(destBranchUrl, git);
			
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
							workspace.setCorrupt(true);
						}
					} else {
						git
								.push()
								.setPushAll()
								.setRemote("origin")
								.setCredentialsProvider(credentials)
								.call();
					}
					
					return res;
				}
			} finally {
				workspace.unlock();
			}
			
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void checkout(String destBranchUrl, Git git) throws GitAPIException {
		git
				.checkout()
				.setCreateBranch(false)
				.setName(destBranchUrl)
				.call(); 
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
				if (uri.toString().contains(baseUrl)) {
					return Arrays.asList(new Proxy(Type.HTTP, InetSocketAddress
		                    .createUnresolved(host, port)));
				} else {
					return delegate == null ? Arrays.asList(Proxy.NO_PROXY)
			                : delegate.select(uri);
				}
			}
			
			@Override
			public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
				if (uri.toString().contains(baseUrl)) {
					throw new RuntimeException("GitVCS proxy connect failed");
				}
			}
		});
	}

	@Override
	public String getBaseUrl() {
		return baseUrl; 
	}

	@Override
	public String getFileContent(String branchName, String filePath, String encoding) {
		try {
			VCSWorkspace workspace = VCSWorkspace.getLockedWorkspace(repoFolder);
			try {
				try (Git git = getLocalGit(workspace)) {
					
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
					File file = new File(workspace.getFolder(), filePath);
					
					return IOUtils.toString(file.toURI(), encoding);
				}
							
			} finally {
				workspace.unlock();
			}
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (IOException e) {
			throw new EVCSFileNotFound(String.format("File %s is not found", filePath));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void setFileContent(String branchName, String filePath, String content, String commitMessage) {
		try {
			VCSWorkspace workspace = VCSWorkspace.getLockedWorkspace(repoFolder);
			try {
				try (Git git = getLocalGit(workspace)) {
					
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
					
					File file = new File(workspace.getFolder(), filePath);
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
				}
							
			} finally {
				workspace.unlock();
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
