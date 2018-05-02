# OSSCI Job DSL repository

This is a [Job DSL](https://github.com/jenkinsci/job-dsl-plugin) project
for FAIR/AML's OSSCI infrastructure.

Looking for information about the actual machines the jobs run on?
See [fairinternal/ossci-infra](https://github.com/fairinternal/ossci-infra).
Some day we will move this repo.

## CI failed, but my local build is fine. What should I do?!

### Cheat sheet

If this is your first time, see "Getting setup".

- Search for `docker pull` in your build log and search for something
  like `308535385114.dkr.ecr.us-east-1.amazonaws.com/pytorch/pytorch-linux-trusty-py3.6-gcc5.4:173-5910`;
  this is your docker image.  (If the tag is `###-####`, it comes with
  a build of your source; if it's `###` that's the stock image.)
- Run `aws ecr get-login` with your AWS credentials to get your Docker
  login command.  Run this command to login.  (If you get the
  error `unknown shorthand flag: 'e' in -e`, try deleting `-e none` from
  the command line.
- Run `docker run -it $DOCKER_IMAGE`

Try prepending sudo if you get the `permission denied` error for the docker commands
(and later figure out why your user doesn't have permissions to connect
to the Docker socket; maybe you need to add yourself to the docker
group).

Want to run a Docker image on a GPU?  Standard issue devgpus don't
allow use of Docker, so you will have to either (1) run docker
on devfair, (2) get a GPU-enabled AWS instance (the OSS CI
team has a few allocated, get in touch with them to see how
to connect), (3) find a GPU machine that you're managing yourself.
All of these will require some time to provision, so don't try to
do this last minute.

Want to know more about what Docker images are available? See
"Available docker images."

### Getting setup

You will need to get authorized for the docker registry.  Contact
@ezyang, @pietern or @pmenglund for authorization.

For the admins:

- Create a new user for the person in IAM
- Issue them an access key, and send it to them

You'll get an access key like AKIABLAHBLAHBLAH and a longer secret
access key.  Use them as username/password for `aws ecr get-login`.
Go to the "cheat sheet"

By the way, to log into AWS Console itself (not useful for pulling
a Docker image, but maybe you want to know), go to
https://console.aws.amazon.com/console/home?region=us-east-1
and type 'caffe2' (no quotes) into the text box and click "Next".
This will take you to the true login window where you can type
in your username and password.

### Available Docker images

If you just want to reproduce a test error, there is the particular
Docker image for your job which you should pull and test.  But if you're
interested in repurposing our CI Docker images for other purposes,
it helps to know about the general structure of the Docker images our
CI exposes and how they are built (so you can find the URL for a base
image you might be interested in.)

For historical reasons, there are two sets of Docker images, one for
PyTorch and one for Caffe2 (we intend to merge these at some point, but
we haven't finished yet.

PyTorch Dockerfiles source lives at https://github.com/pietern/pytorch-dockerfiles
and are built every week at https://ci.pytorch.org/jenkins/job/pytorch-docker-master/

Caffe2 Dockerfiles source lives at https://github.com/pytorch/pytorch/tree/master/docker/caffe2/jenkins
and are built upon request at https://ci.pytorch.org/jenkins/job/caffe2-docker-trigger/

### Advanced tricks

**Summary for CPU:**

    ssh ubuntu@$CPU_HOST
    docker run --rm --cap-add=SYS_PTRACE --security-opt seccomp=unconfined -t -u jenkins -i $DOCKER_IMAGE /bin/bash

**Summary for GPU**

    ssh ubuntu@$GPU_HOST
    docker run --rm --cap-add=SYS_PTRACE --security-opt seccomp=unconfined -t -u jenkins -i --runtime=nvidia -e CUDA_VERSION=8 -e NVIDIA_VISIBLE_DEVICES=all $DOCKER_IMAGE /bin/bash

**What is my CPU/GPU HOST?**

- If you don't need the exact same hardware, you can run these commands
  on any machine that has Docker
- Look in [ssh config in pietern/ossci-infra](https://github.com/pietern/ossci-infra/blob/master/etc/ssh_config),
  which has working cached IP addresses for CPU Linux and OS X
  (GPU Linux is not working as we move to elastic).  You'll need
  your public keys on the machines.
- The canonical information about all our running instances can be found
  on [AWS console](https://console.aws.amazon.com/ec2/v2/home?&region=us-east-1#Instances:sort=desc:tag:Name);
  you'll need a login under the 'caffe2' account, ask @pietern for
  access.

**What is my Docker image?**

Your Docker image will look something like
`registry.pytorch.org/pytorch/pytorch-linux-xenial-cuda9-cudnn7-py2:69-3002`.

This image is:
- `COMMIT_DOCKER_IMAGE` in the build log, and
- `DOCKER_IMAGE` in the test log

You can tell you've got the right one because Jenkins homedir
will have a `workspace` directory.

**What do I do once I'm in?**

Read the actual [jobs](https://github.com/pietern/ossci-job-dsl/tree/master/jobs)
directory to see how to actually build/test (at the very least, you will
need to set `PATH` to pick up the correct Python executable.)

You **DO NOT** need to build PyTorch; it will already be installed.

#### Appendix

**What do all the flags in the docker run command mean?**

* The `--rm` argument ensures that the Docker image gets immediately
  deleted when you exit.  If you don't want this, delete `--rm`...
  but don't forget to `docker rm` the image when you are done
  (stopping it is not sufficient.)

* By default Docker does not enable ptrace, which means that you will
  have a hard time running gdb inside the docker image.
  `--cap-add=SYS_PTRACE --security-opt seccomp=unconfined` ensures
  this capability is allowed.

* The docker image will have a pretty bare set of installed packages.
  To install more, run `sudo apt update` and then `sudo apt install`
  for the packages you want.  `gdb` and `vim` can be quite useful.

**The CUDA docker command didn't work.**

You need to install [nvidia-docker 2.0](https://github.com/NVIDIA/nvidia-docker/tree/2.0)
which knows how to expose CUDA devices inside Docker.

**Where is my source?**

Caffe2 builds don't currently store their source code in test images;
you will need to git clone a copy of the source and checkout the correct
one.

### Mac OS X

OS X builds are not containerized.  To SSH into an OS X machine, run:

    ssh administrator@208.52.182.75
    ssh administrator@208.52.182.225

If these IP addresses do not work, here is some more canonical
information:

- Look in [ssh config in pietern/ossci-infra](https://github.com/pietern/ossci-infra/blob/master/etc/ssh_config).
- Check the [AWS console](https://console.aws.amazon.com/ec2/v2/home?&region=us-east-1#Instances:sort=desc:tag:Name)

Changes you make to these machines affect everyone, so please be careful.

### Windows

You must Remote Desktop into the Windows machines; see [this
Quip](https://fb.quip.com/5itGAsp277me) for information how to access
(you may have to ask for access.)

Changes you make to these machines affect everyone, so please be careful.

## Job structure

Taking PyTorch as an example (much of the same applies to Caffe2),
here is how we structure our jobs:

* The pytorch-docker builds are responsible for taking the Dockerfiles
  from [pytorch-dockerfiles](https://github.com/pietern/pytorch-dockerfiles) and building
  Docker images.  These images are uploaded to
  `registry.pytorch.org/pytorch`.  Every new built Docker image gets a new
  tag, which is a sequentially incrementing number.

    * Docker builds are layered; for example, we will image an
      Ubuntu Xenial image, and then on top of it, build both CUDA 8 and
      CUDA 9 images, and then on top of those, build Python 2 and
      Python 3 images.

    * After a Docker build completes, we test and make sure that master
      of PyTorch builds with the new image (in case changes in the image
      introduced a regression.)  If it passes, the Docker build process
      will deploy the image, by making a commit "Update PyTorch DockerVersion"
      which updates the latest PyTorch DockerVersion (the tag, really)
      in `./src/main/groovy/ossci/pytorch/DockerVersion.groovy`.
      (You can also manually update the active DockerVersion by editing
      this file.)

* PyTorch builds are intermediated by a top level trigger build for
  commits to master, and pull requests (using Jenkins GitHub Pull
  Request Builder).

    * This kicks off parallel builds for each system configuration
      we are interested in (at the moment, only Python 2 and Python 3,
      but we will be adding CUDA 8 and CUDA 9 permutations as well.)

    * The configuration build itself is split into two phases.  The
      first phase *only* builds and installs PyTorch into the Docker
      image.  We then push the Docker image to the registry.  This
      build is done on a CPU-only machine.  The second phase tests
      PyTorch on a GPU provisioned machine by loading the Docker image.

* There are also miscellaneous cronjobs for cleaning old Docker images
  from the registry and the local builders.

## Common job DSL gotchas

* Essentially *all* jobs should have `concurrentBuild()` set.  Don't
  forget it, or your builds may queue up.

## File structure

    .
    ├── jobs                    # DSL script files
    ├── resources               # resources for DSL scripts
    ├── src
    │   ├── main
    │   │   ├── groovy          # support classes
    │   │   └── resources
    │   │       └── idea.gdsl   # IDE support for IDEA
    │   └── test
    │       └── groovy          # specs
    └── build.gradle            # build file

## Testing

`./gradlew test` runs the specs.

[JobScriptsSpec](src/test/groovy/com/dslexample/JobScriptsSpec.groovy) 
will loop through all DSL files and make sure they don't throw any exceptions when processed. All XML output files are written to `build/debug-xml`. 
This can be useful if you want to inspect the generated XML before check-in.

## Seed Job

You can create the example seed job via the Rest API Runner (see below) using the pattern `jobs/seed.groovy`.

Or manually create a job with the same structure:

* Invoke Gradle script
   * Use Gradle Wrapper: `true`
   * Tasks: `clean test`
* Process Job DSLs
   * DSL Scripts: `jobs/**/*Jobs.groovy`
   * Additional classpath: `src/main/groovy`
* Publish JUnit test result report
   * Test report XMLs: `build/test-results/**/*.xml`

Note that starting with Job DSL 1.60 the "Additional classpath" setting is not available when
[Job DSL script security](https://github.com/jenkinsci/job-dsl-plugin/wiki/Script-Security) is enabled.

## REST API Runner

Note: the REST API Runner does not work with [Automatically Generated DSL](https://github.com/jenkinsci/job-dsl-plugin/wiki/Automatically-Generated-DSL). 

A gradle task is configured that can be used to create/update jobs via the Jenkins REST API, if desired. Normally
a seed job is used to keep jobs in sync with the DSL, but this runner might be useful if you'd rather process the
DSL outside of the Jenkins environment or if you want to create the seed job from a DSL script.

```./gradlew rest -Dpattern=<pattern> -DbaseUrl=<baseUrl> [-Dusername=<username>] [-Dpassword=<password>]```

* `pattern` - ant-style path pattern of files to include
* `baseUrl` - base URL of Jenkins server
* `username` - Jenkins username, if secured
* `password` - Jenkins password or token, if secured

## Miscellaneous tips

Sometimes, you will be looking for a function in the Jenkins
Job DSL, and it will simply not exist.  DO NOT DESPAIR.
Read this instead: http://www.devexp.eu/2014/10/26/use-unsupported-jenkins-plugins-with-jenkins-dsl/

In particular, http://job-dsl.herokuapp.com/ is really helpful, even
if you're not necessarily working on a custom DSL function.

When you do this, you might want to edit the web UI, and then
see the Jenkins? Click on "REST API" at the bottom of the job
page and click the link for "config.xml", which will give you the
config.xml of the job.  Example: https://ci.pytorch.org/jenkins/job/skeleton-pull-request/config.xml

## Useful Groovy scripts

You can navigate to https://ci.pytorch.org/jenkins/script and run Groovy scripts to run ad hoc management tasks.
This can be very useful for tasks that are tedious to execute manually.

**Beware: with great power comes great responsibility!!**

### Mass removal of stale jobs

```groovy
import jenkins.model.*
  
def folder = Jenkins.instance.items.find { job ->
  job.name == "caffe2-builds"
}

def jobs = folder.items.findAll { job -> 
  job.name =~ /^caffe2-linux-/
}

jobs.each { job -> 
  println("Planning to remove ${job.name}") 
  //job.delete()
}

null
```

### Listing the number of queued items waiting on what labels

```
def map = [:]

Jenkins.instance.queue.items.each {
    i = map.get(it.assignedLabel, 0);
    map[it.assignedLabel] = i + 1;
}

sorted = map.sort { a, b -> b.value <=> a.value }

sorted.each { label, count ->
    println("${label}: ${count}");
}

println "---"

Jenkins.instance.slaves.each {
  println "${it.name} (${it.getComputer().countBusy()}/${it.getNumExecutors()}): ${it.getLabelString()}"
}

null
```

### Pruning stale queued jobs

```groovy
import hudson.model.*
  
def queue = Hudson.instance.queue
  
def cancel = queue.items.findAll {
  if (it.task.name.startsWith('ccache-cleanup')) {
    return true;
  }
  if (it.task.name.startsWith('docker-image-cleanup')) {
    return true;
  }
  if (it.task.name.startsWith('workspace-cleanup')) {
    return true;
  }
  return false;
}

cancel.each {
  queue.cancel(it.task)
}
```

### Developing using IntelliJ

A more pleasant Java development experience can be attained by working
on ossci-job-dsl inside a real Java IDE.  Here's how to set it up using
IntelliJ:

1. In the opening splash screen, select "Import Project"
2. Select the directory of ossci-job-dsl
3. Import project from external model: Gradle
4. Click through the last screen, finishing the import
5. To test, click "Run" and "Edit configurations"
6. Create a new run configuration based on Gradle
7. Select the current project as the Gradle project, and put "test" in
   Tasks.

You now have running tests!
