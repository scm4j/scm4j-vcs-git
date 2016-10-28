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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand.ListMode;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.diff.DiffEntry.Side;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import com.projectkaiser.scm.vcs.api.IVCS;
import com.projectkaiser.scm.vcs.api.VCSChangeType;
import com.projectkaiser.scm.vcs.api.VCSDiffEntry;
import com.projectkaiser.scm.vcs.api.VCSMergeResult;
import com.projectkaiser.scm.vcs.api.exceptions.EVCSBranchExists;
import com.projectkaiser.scm.vcs.api.exceptions.EVCSException;
import com.projectkaiser.scm.vcs.api.exceptions.EVCSFileNotFound;
import com.projectkaiser.scm.vcs.api.workingcopy.IVCSLockedWorkingCopy;
import com.projectkaiser.scm.vcs.api.workingcopy.IVCSRepositoryWorkspace;

public class GitVCS implements IVCS {

	private static final String MASTER_BRANCH_NAME = "master";
	public static final String GIT_VCS_TYPE_STRING = "git";
	private static final String REFS_REMOTES_ORIGIN = "refs/remotes/origin/";

	private CredentialsProvider credentials;
	private IVCSRepositoryWorkspace repo;
	
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
					try {
						
						git
								.checkout()
								.setCreateBranch(git.getRepository().exactRef("refs/heads/" + 
											parseBranch(srcBranchName)) == null)
								.setStartPoint("origin/" + parseBranch(srcBranchName))
								.setName(parseBranch(srcBranchName))
								.call(); // switch to master
					
						git
								.branchCreate()
								.setUpstreamMode(SetupUpstreamMode.TRACK)
								.setName(newBranchName)
								.call();
						
						RefSpec refSpec = new RefSpec().setSourceDestination(newBranchName, 
								newBranchName); 
						git
								.push()
								.setRefSpecs(refSpec)
								.setCredentialsProvider(credentials)
								.call();
						Thread.sleep(2000); // github has some latency on branch operations
											// so next request branches operation will return old branches list
					} finally {
						git.getRepository().close();
					}
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

	private String parseBranch(String branchName) {
		return branchName == null ? MASTER_BRANCH_NAME : branchName;
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
					Thread.sleep(2000); // github has some latency on branch operations
										// so next request branches operation will return old branches list
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
	public VCSMergeResult merge(String sourceBranchUrl, String destBranchUrl, String commitMessage) {
		try {
			try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy()) {
				try (Git git = getLocalGit(wc)) {
					
					git
							.pull()
							.setCredentialsProvider(credentials)
							.call();
					
					git
							.checkout()
							.setCreateBranch(git.getRepository().exactRef("refs/heads/" + parseBranch(destBranchUrl)) == null)
							.setName(parseBranch(destBranchUrl))
							.call(); 
			
					MergeResult mr = git
							.merge()
							.include(git.getRepository().findRef("origin/" + parseBranch(sourceBranchUrl)))
							.setMessage(commitMessage)
							.call(); // actually do the merge
			
			
					VCSMergeResult res = new VCSMergeResult();
					
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
				String bn = parseBranch(branchName);
				git
						.pull()
						.setCredentialsProvider(credentials)
						.call();
				
				git
						.checkout()
						.setCreateBranch(git.getRepository().exactRef("refs/heads/" + bn) == null)
						.addPath(fileRelativePath)
						.setName(bn)
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
					String bn = parseBranch(branchName);
					
					git
							.pull()
							.setCredentialsProvider(credentials)
							.call();
			
					git
							.checkout()
							.setCreateBranch(git.getRepository().exactRef("refs/heads/" + bn) == null)
							.setName(bn)
							.call();
					
					File file = new File(wc.getFolder(), filePath);
					if (!file.exists()) {
						FileUtils.forceMkdir(file.getParentFile());
						file.createNewFile();
						git
								.add()
								.addFilepattern(filePath)
								.call();
					}
					FileWriter fw = new FileWriter(file, false);
					fw.write(content);
					fw.close();
					
					git
							.commit()
							.setOnly(filePath)
							.setMessage(commitMessage)
							.call();
					
					RefSpec refSpec = new RefSpec(bn + ":" + bn);
					
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

	@Override
	public List<VCSDiffEntry> getBranchesDiff(String srcBranchName, String destBranchName) {
		List<VCSDiffEntry> res = new ArrayList<>();
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy()) {
			try (Git git = getLocalGit(wc)) {
		        try (RevWalk walk = new RevWalk(git.getRepository())) {
			        
			        RevCommit srcHeadCommit = walk.parseCommit(git.getRepository().resolve("remotes/origin/" 
			        		+ parseBranch(srcBranchName)));
			        RevCommit destHeadCommit = walk.parseCommit(git.getRepository().resolve("remotes/origin/" 
			        		+ parseBranch(destBranchName)));
					
					List<RevCommit> startPoints = new ArrayList<RevCommit>();
					walk.setRevFilter(RevFilter.MERGE_BASE);
					startPoints.add(destHeadCommit);
					startPoints.add(srcHeadCommit);
	
					walk.markStart(startPoints);
					RevCommit forkPoint = walk.next();
					
		            ObjectReader reader = git.getRepository().newObjectReader();
		            CanonicalTreeParser srcTreeIter = new CanonicalTreeParser();
		            srcTreeIter.reset(reader, srcHeadCommit.getTree());
		            
		            CanonicalTreeParser destTreeIter = new CanonicalTreeParser();
		            destTreeIter.reset(reader, forkPoint.getTree());

		            List<DiffEntry> diffs = git
		            		.diff()
		                    .setNewTree(srcTreeIter)
		                    .setOldTree(destTreeIter)
		                    .call();
		            
		            for (DiffEntry diffEntry : diffs) {
			        	VCSDiffEntry vcsEntry = new VCSDiffEntry(
			        			diffEntry.getPath(diffEntry.getChangeType() == ChangeType.ADD ? Side.NEW : Side.OLD), 
			        			gitChangeTypeToVCSChangeType(diffEntry.getChangeType()));
			        	res.add(vcsEntry);
			        }
		        }
	            
		        git.getRepository().close();
		        return res;
			}
		} catch (GitAPIException e) { 
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		
	}

	private VCSChangeType gitChangeTypeToVCSChangeType(ChangeType changeType) {
		switch (changeType) {
		case ADD:
			return VCSChangeType.ADD;
		case DELETE:
			return VCSChangeType.DELETE;
		case MODIFY:
			return VCSChangeType.MODIFY;
		default:
			return VCSChangeType.UNKNOWN;
		}
	}

	@Override
	public Set<String> getBranches() {
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy()) {
			try (Git git = getLocalGit(wc)) {
				List<Ref> refs = git
						.branchList()
						.setListMode(ListMode.REMOTE)
						.call();
				Set<String> res = new HashSet<>();
				for (Ref ref : refs) {
					res.add(ref.getName().replace(REFS_REMOTES_ORIGIN, ""));
				}
				return res;
			}
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public List<String> getCommitMessages(String branchName, Integer limit) {
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy()) {
			try (Git git = getLocalGit(wc)) {
				
				Iterable<RevCommit> logs = git
						.log()
						.add(git.getRepository().resolve("remotes/origin/" 
									+ parseBranch(branchName)))
						.setMaxCount(limit)
						.call();
				
				List<String> res = new ArrayList<>();
				for (RevCommit commit : logs) {
					res.add(commit.getFullMessage());
				}
				git.getRepository().close();
				return res;
			}
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public String getVCSTypeString() {
		return GIT_VCS_TYPE_STRING;
	}

	@Override
	public void removeFile(String branchName, String filePath, String commitMessage) {
		try (IVCSLockedWorkingCopy wc = repo.getVCSLockedWorkingCopy()) {
			try (Git git = getLocalGit(wc)) {
				String bn = parseBranch(branchName);
				git
						.pull()
						.setCredentialsProvider(credentials)
						.call();
				
				git
						.checkout()
						.setCreateBranch(git.getRepository().exactRef("refs/heads/" + bn) == null)
						.setName(bn)
						.call();
				
				git
						.rm()
						.addFilepattern(filePath)
						.setCached(false)
						.call();
				
				git
						.commit()
						.setMessage(commitMessage)
						.setAll(true)
						.call();
				
				git
						.push()
						.setRemote("origin")
						.setCredentialsProvider(credentials)
						.call();
						
				git.getRepository().close();
			}
		} catch (GitAPIException e) {
			throw new EVCSException(e);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
