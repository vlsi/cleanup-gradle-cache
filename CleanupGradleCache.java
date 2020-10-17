import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Properties;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class CleanupGradleCache {
  private static final ThreadLocal<Checksum> CHECKSUM = ThreadLocal.withInitial(Checksum::new);
  private static final AtomicLong BYTES_REMOVED = new AtomicLong();
  private static final AtomicLong FILES_REMOVED = new AtomicLong();
  private static final Pattern IS_HEX = Pattern.compile("^[0-9a-f]+$");
  private static boolean dryRun = false;
  private static boolean keepOldVersions = false;
  private static boolean verifyChecksums = true;
  private static boolean keepLockFiles = false;
  private static boolean keepUserId = false;
  private static boolean keepUnzippedDistributions = false;
  private static boolean verbose = false;

  static class Checksum {
    private final byte[] buffer = new byte[8192];
    private final MessageDigest digest;

    {
      try {
        digest = MessageDigest.getInstance("SHA1");
      } catch (NoSuchAlgorithmException e) {
        throw new RuntimeException("SHA1 is not implemented", e);
      }
    }

    public String compute(Path file) {
      MessageDigest md = digest;
      md.reset();
      try (InputStream stream = Files.newInputStream(file);) {
        int n;
        while ((n = stream.read(buffer)) != -1) {
          md.update(buffer, 0, n);
        }
        return new BigInteger(1, md.digest()).toString(16);
      } catch (IOException e) {
        System.err.println("Error while processing " + file + ": " + e.getMessage());
        e.printStackTrace();
      }
      return null;
    }
  }

  private static void debug(Supplier<String> message) {
    if (verbose) {
      System.out.println(message.get());
    }
  }

  public static void main(String[] args) throws Throwable {
    for (String arg : args) {
      if ("--dry-run".equals(arg)) {
        dryRun = true;
      } else if ("--keep-old-versions".equals(arg)) {
        keepOldVersions = true;
      } else if ("--keep-lock-files".equals(arg)) {
        keepLockFiles = true;
      } else if ("--keep-user-id".equals(arg)) {
        keepUserId = true;
      } else if ("--keep-unzipped-distributions".equals(arg)) {
        keepUnzippedDistributions = true;
      } else if ("--verbose".equals(arg)) {
        verbose = true;
      } else if ("--verify-checksums".equals(arg)) {
        verifyChecksums = true;
      } else {
        System.out.println("CleanupGradleCache utility");
        boolean badOption = !"-?".equals(arg) && !"-h".equals(arg) && !arg.endsWith("-help");
        if (badOption) {
          System.out.println("Unrecognized option: " + arg);
        }
        System.out.println(
            "Usage: java CleanupGradleCache [options]\n" +
                "  --verbose           (default: false) print verbose output\n" +
                "  --dry-run           (default: false) run as usual, however, avoid file " +
                "removals\n" +
                "  --keep-old-versions (default: false) keep old Gradle distributions (by " +
                "default, all are removed except the version in wrapper)\n" +
                "  --keep-lock-files   (default: false) keep journal-1.lock and modules-2.lock files\n" +
                "  --keep-user-id      (default: false) keep user-id.txt and user-id.txt.lock files\n" +
                "  --keep-unzipped-distributions (default: false) keep transforms-2/.../unzipped-distributions\n" +
                "  --verify-checksums  (default: true) verify checksums for files in " +
                "caches/modules-2/files-2.1");
        System.exit(badOption ? 1 : 0);
      }
    }
    System.out.println("Gradle cache cleanup utility");
    Path gradleHome = Paths.get(System.getProperty("user.home"), ".gradle");

    if (keepLockFiles) {
      debug(() -> "Removal of journal-1.lock and modules-2.lock is disabled");
    } else {
      removeFile(gradleHome.resolve("caches/journal-1/journal-1.lock"));
      removeFile(gradleHome.resolve("caches/modules-2/modules-2.lock"));
    }
    if (keepUserId) {
      debug(() -> "Removal of caches/user-id.txt is disabled");
    } else {
      removeFile(gradleHome.resolve("caches/user-id.txt"));
      removeFile(gradleHome.resolve("caches/user-id.txt.lock"));
    }
    if (keepUnzippedDistributions) {
      debug(() -> "Removal of unzipped-distribution is disabled");
    } else {
      removeUnzippedDistributions(gradleHome.resolve("caches/transforms-2/files-2.1"));
    }
    versionDependentCleanup(gradleHome);
    verifyChecksums(gradleHome);
    System.out.println("Removed " + FILES_REMOVED.get() + " files, " +
        BYTES_REMOVED.get() + " bytes");
  }

  private static void removeUnzippedDistributions(Path path) throws IOException {
    if (!path.toFile().isDirectory()) {
      debug(() -> path.toString() + " is not a directory, so will skip unzipped-distribution removal");
      return;
    }
    try (Stream<Path> walk = Files.walk(path, 2)) {
      walk.filter(file -> file.getFileName().toString().equals("unzipped-distribution"))
          .forEach(dir -> {
            try {
              removeDir(dir, "unzipped distribution");
            } catch (IOException e) {
              System.err.println("Unable to remove " + dir);
              e.printStackTrace();
            }
          });
    }
  }

  private static void versionDependentCleanup(Path gradleHome) throws IOException {
    File wrapper = new File("gradle/wrapper/gradle-wrapper.properties");
    String distributionName = null;
    String distributionVersion = null;
    Path distributionPath;
    if (!wrapper.isFile()) {
      System.err.println("Gradle wrapper properties file is not found: " + wrapper);
      distributionPath = gradleHome.resolve("wrapper/dists");
    } else {
      Properties props = new Properties();
      try (InputStream is = new FileInputStream(wrapper)) {
        props.load(is);
      }

      String distributionBaseStr = props.getProperty("distributionBase");
      Path distributionBase;
      if ("GRADLE_USER_HOME".equals(distributionBaseStr)) {
        distributionBase = Paths.get(System.getProperty("user.home"), ".gradle");
      } else {
        distributionBase = Paths.get(distributionBaseStr);
      }

      String distributionUrl = props.getProperty("distributionUrl");
      // distributionUrl.substringAfterLast("/")
      distributionName = distributionUrl.substring(distributionUrl.lastIndexOf('/') + 1);
      if (distributionName.endsWith(".zip")) {
        distributionName = distributionName.substring(0,
            distributionName.length() - ".zip".length());
      }
      distributionVersion = distributionName;
      if (distributionVersion.startsWith("gradle-")) {
        distributionVersion = distributionVersion.substring("gradle-".length());
      }
      if (distributionVersion.endsWith("-all") || distributionVersion.endsWith("-bin")) {
        distributionVersion = distributionVersion.substring(0, distributionVersion.length() - "-bin"
            .length());
      }

      distributionPath = distributionBase.resolve(props.getProperty("distributionPath"));
    }

    removeStaleWrappers(distributionPath, distributionName);
    if (!keepOldVersions && distributionVersion != null) {
      removeStaleCaches(gradleHome, distributionVersion);
    }
  }

  private static void removeStaleCaches(Path gradleHome, String distributionVersion) throws IOException {
    System.out.println("Removing caches from the stale versions");
    File caches = gradleHome.resolve("caches").toFile();
    if (!caches.exists()) {
      return;
    }
    for (File file : orEmpty(caches.listFiles((File dir, String name) ->
        Character.isDigit(name.charAt(0)) && !distributionVersion.equals(name)))) {
      removeDir(file.toPath(), "cache from old Gradle version");
    }
  }

  private static void removeStaleWrappers(Path distributions,
      String distributionName) throws IOException {
    if (keepOldVersions || distributionName == null) {
      System.out.println("Removing zip files from " + distributions);
    } else {
      System.out.println("Removing old Gradle distributions from " + distributions +
          " (will keep " + distributionName + ")");
    }
    File[] files = distributions.toFile().listFiles();
    for (File file : orEmpty(files)) {
      if (!keepOldVersions && distributionName != null
          && !distributionName.equals(file.getName())) {
        removeDir(file.toPath(), "stale wrapper distribution");
      } else {
        System.out.println("Removing distribution zip from " + file);
        try (Stream<Path> walk = Files.walk(file.toPath(), 2)) {
          walk.filter(x -> x.getFileName().toString().endsWith(".zip"))
              .forEach(CleanupGradleCache::removeFile);
        }
      }
    }
  }

  private static void verifyChecksums(Path gradleHome) throws IOException {
    if (!verifyChecksums) {
      debug(() -> "Checksum verification is disabled");
      return;
    }
    Path files21 = gradleHome.resolve("caches/modules-2/files-2.1");
    System.out.println("Verifying checksums in " + files21);
    ForkJoinPool pool = ForkJoinPool.commonPool();
    try (Stream<Path> walk = Files.walk(files21)) {
      walk.parallel().forEach(file -> pool.execute(() -> verifyChecksum(file)));
    }
    if (!pool.awaitQuiescence(120, TimeUnit.SECONDS)) {
      System.out.println("There are " + pool.getQueuedTaskCount() + " tasks still executing in " + pool);
    }
  }

  private static void verifyChecksum(Path file) {
    if (!file.toFile().isFile()) {
      return;
    }
    int nameCount = file.getNameCount();
    if (nameCount < 2) {
      debug(() -> "filename is too short " + file);
      return;
    }
    String dirName = file.getName(nameCount - 2).toString();
    if (!IS_HEX.matcher(dirName).matches()) {
      debug(() -> "Directory name should look like a checksum " + IS_HEX + ": " + dirName + " " +
          "(file is " + file + ")");
      return;
    }
    String actual = CHECKSUM.get().compute(file);
    if (!dirName.equals(actual)) {
      System.out.println("Checksum mismatch for " + file +
          " (expected " + dirName + ", actual: " + actual + ")");
      removeFile(file);
    }
  }

  private static File[] orEmpty(File[] files) {
    return files == null ? new File[0] : files;
  }

  private static void removeDir(Path rootPath, String message) throws IOException {
    System.out.println("Removing " + message + ": " + rootPath);
    // Does not follow the symlinks
    try (Stream<Path> walk = Files.walk(rootPath)) {
      walk.sorted(Comparator.reverseOrder())
          .map(Path::toFile)
          .forEach(CleanupGradleCache::removeFileSilent);
    }
  }

  private static void removeFile(Path path) {
    removeFile(path.toFile());
  }

  private static void removeFile(File file) {
    if (!file.exists()) {
      debug(() -> "File " + file + " does not exist, won't remove it");
      return;
    }
    BYTES_REMOVED.getAndAdd(file.length());
    FILES_REMOVED.getAndIncrement();
    if (dryRun || file.delete()) {
      System.out.println("Removed " + file);
    } else {
      System.out.println("Unable to remove " + file);
    }
  }

  private static void removeFileSilent(File file) {
    if (!file.exists()) {
      debug(() -> "File " + file + " does not exist, won't remove it");
      return;
    }
    BYTES_REMOVED.getAndAdd(file.length());
    FILES_REMOVED.getAndIncrement();
    if (dryRun || file.delete()) {
      // silent
    } else {
      System.out.println("Unable to remove " + file);
    }
  }
}
