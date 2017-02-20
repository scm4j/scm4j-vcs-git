[![Release](https://jitpack.io/v/ProjectKaiser/pk-vcs-git.svg)](https://jitpack.io/#ProjectKaiser/pk-vcs-git)	

# Overview
Pk-vcs-git is lightweight library for execute basic Git VCS operations (merge, branch create etc). It uses [pk-vcs-api](https://github.com/ProjectKaiser/pk-vcs-api) exposing IVCS implementation for Git repositories and [JGit](https://eclipse.org/jgit/) as framework to work with Git repositories.
Features:
- Branch create and remove
- Branch merge with result return (success or list of conflicted files)
- Branch commits messages list
- Summarized diff between branches
- Branches list
- File content getting and setting
- File create and remove

# Terms
- Workspace Home
  - Home folder of all folders used by vcs-related operations. See [pk-vcs-api](https://github.com/ProjectKaiser/pk-vcs-api) for details
- Repository Workspace
  - Folder for LWC folders related to Repository of one type. See [pk-vcs-api](https://github.com/ProjectKaiser/pk-vcs-api) for details
- Locked Working Copy, LWC
  - Folder where vcs-related operations are executed. Provides thread- and process-safe repository of working folders. See [pk-vcs-api](https://github.com/ProjectKaiser/pk-vcs-api) for details
- Test Repository
  - Git repository which is used to execute functional tests
  - Hosted on [Github](https://github.com/) using username and password provided by environment variables (see below)
  - Generates new before and deletes after each test
  - Named randomly (uuid is used) 
  - [Kohsuke Github API](http://github-api.kohsuke.org/) is used as to operate with Github API

# Using pk-vcs-git
- Add github-hosted pk-vcs-git project as maven dependency using [jitpack.io](https://jitpack.io/). As an example, add following to gradle.build file:
	```gradle
	allprojects {
		repositories {
			maven { url "https://jitpack.io" }
		}
	}
	
	dependencies {
	 	// versioning: master-SNAPSHOT (lastest build, unstable), + (lastest release, stable) or certain version (e.g. 1.0)
		compile 'com.github.ProjectKaiser:pk-vcs-git:master-SNAPSHOT'
	}
	```
	Or download release jars from https://github.com/ProjectKaiser/pk-vcs-api/releases  
- Create Workspace Home instance providing path to any folder as Workspace Home folder path. This folder will contain repositories folders (if different vcs or repositories are used)
```java
	public static final String WORKSPACE_DIR = System.getProperty("java.io.tmpdir") + "git-workspaces";
	...
	IVCSWorkspace workspace = new VCSWorkspace(WORKSPACE_DIR);
	...
```
- Obtain Repository Workspace from Workspace Home providing a certain Repository's url. The obtained Repository Workspace will represent a folder within Workspace Home dir which will contain all Working Copies relating to the provided VCS Repository  
```java
	String repoUrl = "https://github.com/ProjectKaiser/pk-vcs-api";
	IVCSRepositoryWorkspace repoWorkspace = workspace.getVCSRepositoryWorkspace(repoUrl);
```
- Create `GitVCS` instance providing Repository Workspace
```java
	IVCS vcs = new GitVCS(repoWorkspace);
```
- Use methods of `IVCS` interface. See [pk-vcs-api](https://github.com/ProjectKaiser/pk-vcs-api) for details
- Use `vcs.setProxy()` and `vcs.setCredentials()` if necessary
- Github has some latency for exposing results of previously executed operations. For example if create a new branch and immediately check branches list then Github could return old branches list. Use `GitVCS.setExpectedLatency()` to set delay which will be executed after each operation which may have server latency 

# Implementation details
- [JGit](https://eclipse.org/jgit/) is used as framework to work with Git repositories
- [Github](https://github.com/) is used as hosting of a Test Repository
- LWC is obtained for each vcs operation.
- `getLocalGit(IVCSLockedWorkingCopy wc)` method is used to create a Git implementation to execute vcs operations within `wc` Working Copy
  - If provided LWC is empty then current Test Repository is cloned into this LWC, otherwise just Git object is created
- If `IVCS.setProxy()` is called then provided proxy is used for each url which contains `repoUrl`

# Functional testing
- Github is used for hosting the Test Repository
  - [Kohsuke Github API](http://github-api.kohsuke.org/) is used to create and delete Test Repository
  - `PK_VCS_TEST_GITHUB_USER` environment var or JVM var is used as username for access to Github
  - `PK_VCS_TEST_GITHUB_PASS` environment var or JVM var is used as user password for access to Github
  - New Test Repository is created before each test and deletes automatically after each test
- To execute tests just run GitVCSTest class as JUnit test. Tests from VCSAbstractTest class will be executed. See  [pk-vcs-test](https://github.com/ProjectKaiser/pk-vcs-test) for details
- NOTE: Github has some latency for exposing results of previously executed operations. For example if create a new branch and immediately check branches list then Github could return old branches list. If a test is failed then try to execute it again. Also use `GitVCS.setExpectedLatency()` to set delay for 'dangerous' operations.

# Limitations
- Commit messages can not be attached to branch create and delete operations because Git does not exposes these operations as separate commits
