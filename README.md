About
=====

This utility:
* Removes the stale files from `.gradle` folder to make CI caching better
* Removes the corrupted files from `.gradle/caches/modules-2/files-2.1` (see https://github.com/gradle/gradle/issues/14774)

Cleanup Gradle Cache
====================

Makes your builds faster, reduces CI cache usage, and protects you from [cache corruptions](https://github.com/gradle/gradle/issues/14774)

Note: it is recommended to make `buildSrc.jar` reproducible if you use `buildSrc` otherwise Gradle would produce slightly different
`buildSrc.jar` every time and it might trigger CI cache invalidate and upload:

`buildSrc/build.gradle.kts`:

```kotlin
allprojects {
    tasks.withType<AbstractArchiveTask>().configureEach {
        // Ensure builds are reproducible
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        dirMode = "775".toInt(8)
        fileMode = "664".toInt(8)
    }
}
```

Why should I use it?
----------------

The motivation to build the tool was cache corruption for `pgjdbc` and `Apache Calcite` projects at Travis CI.
Of course, the corruption is not often, however, it is annoying to clean the cache manually every time.

Here's a sample from `pgjdbc` analysis: https://github.com/gradle/gradle/issues/14774#issuecomment-709341046

If you see the following error in your build logs, then you are likely hit by this error:

```
> Task :...:compileJava
error: error reading /home/travis/.gradle/caches/modules-2/files-2.1/....jar; error in opening zip file
```

You might detect broken archives if you use [checksum-dependency-plugin](https://github.com/vlsi/vlsi-release-plugins/tree/master/plugins/checksum-dependency-plugin)
or [Gradle Dependency Verification](https://docs.gradle.org/current/userguide/dependency_verification.html) feature.


Recommended configuration for Travis CI
---------------------------------------

`.travis.yml`:

```yaml
cache:
  directories:
    - $HOME/.gradle/caches/
    - $HOME/.gradle/wrapper/
before_cache:
  - F=CleanupGradleCache sh -x -c 'curl -O https://raw.githubusercontent.com/vlsi/cleanup-gradle-cache/v1.x/$F.java && javac -J-Xmx128m $F.java && java -Xmx128m $F'
```

Why Travis recommendations are not enough?
------------------------------------------

Travis [documentation](https://docs.travis-ci.com/user/languages/java#caching) suggests the following configuration:

```yaml
before_cache:
  - rm -f  $HOME/.gradle/caches/modules-2/modules-2.lock
  - rm -fr $HOME/.gradle/caches/*/plugin-resolution/
cache:
  directories:
    - $HOME/.gradle/caches/
    - $HOME/.gradle/wrapper/
```

This configuration contains major drawbacks:
* `.gradle/wrapper/...` contains both Gradle distribution and `gradle-x.y-...zip` archive
    `gradle-x.y-bin.zip` is needed only for unpacking the distribution, however, Gradle does not remove `.zip` file later.

* By default, Gradle keeps old distributions just in case the developer would build a project with a different Gradle version.

    CI server does not need more than one Gradle version, so all the old ones can be removed as soon as the version in
    `gradle-wrapper.properties` is updated.

    See https://github.com/gradle/gradle/issues/7018

* `.gradle/caches` folder contains `user-id.txt` and `user-id.txt.lock` which are recreated by Gradle.

* `plugin-resolution` seems to be related to an old Gradle version, and the folder no longer exists in the current Gradle versions.


What does `cleanup-gradle-cache` do?
------------------------------------

* Removes `journal-1.lock`, `modules-2.lock`, `user-id.txt`, and `user-id.txt.lock` files from `$HOME/.gradle/caches/...`
* Removes all the Gradle distribution `.zip` files from `$HOME/.gradle/wrapper/dists/...`
* Removes outdated Gradle distributions from `$HOME/.gradle/wrapper/dists/...` (it keeps only the currently listed in `gradle-wrapper.properties` version)
* Removes caches for all the outdated Gradle versions from `$HOME/.gradle/caches/...` (it keeps only the currently listed in `gradle-wrapper.properties` version)
* Removes unzipped distributiosn from `$HOME/.gradle/caches/transforms-2/files-2.1` (it might contain `.../unzipped-distribution/...`)
* Verifies checksums for files stored in `$HOME/.gradle/caches/modules-2/files-2.1` (see https://github.com/gradle/gradle/issues/14774)

    Gradle caches remote artifacts in `.gradle/caches/modules-2/files-2.1`, and the [cache might corrupt](https://github.com/gradle/gradle/issues/14774) (e.g. files might become truncated) if Gradle process
    terminates unexpectedly. That results in broken jar in the cache, and Gradle can't heal that on its own yet.

    cleanup-gradle-cache verifies checksums for all the files in `.gradle/caches/modules-2/files-2.1`, and it removes broken files, so broken files are no longer cached.

Command-line options
--------------------

The following options are supported

```
Usage: java CleanupGradleCache [options]
  --verbose           (default: false) print verbose output
  --dry-run           (default: false) run as usual, however, avoid file removals
  --keep-old-versions (default: false) keep old Gradle distributions (by default, 
                                       all are removed except the version in wrapper)
  --keep-lock-files   (default: false) keep journal-1.lock and modules-2.lock files
  --keep-user-id      (default: false) keep user-id.txt and user-id.txt.lock files
  --keep-unzipped-distributions (default: false) keep transforms-2/.../unzipped-distributions
  --verify-checksums  (default: true) verify checksums for files in caches/modules-2/files-2.1
```

Why Java?
---------

Well, initially I tried to implement the script with `find` + `xargs` as follows, however, the list of avaliable options
differs between BSD and non-BSD utilities, so it turned out Java-based implementation is easier to test across different platforms.

```bash
echo Collecting files to verify
find ~/.gradle/caches/modules-2/files-2.1 -type f -print |
  awk -F '/' '$(NF-1) ~ /^[0-9a-f]+$/ { print sprintf("%040s", $(NF-1))" *"$0 }' > checksum.txt

echo Verifying checksums
# rev | cut | rev  cuts ': FAILURE' from the end of the string
shasum -c checksum.txt | grep -v ': OK' | rev | cut -c9- | rev > badfiles.txt

echo Removing invalid files
cat badfiles.txt | tr '\n' '\0' | xargs -0 rm -v
```

License
-------
Apache License 2.0

Change log
----------
v1.0
* Initial release

Author
------
Vladimir Sitnikov <sitnikov.vladimir@gmail.com>
