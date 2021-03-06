/*-
 * #%L
 * owncloud-spring-boot-starter
 * %%
 * Copyright (C) 2016 - 2017 by the original Authors
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package software.coolstuff.springframework.owncloud.service.impl.local;

import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.Validate;
import org.springframework.beans.factory.annotation.Autowired;
import software.coolstuff.springframework.owncloud.exception.resource.OwncloudLocalResourceChecksumServiceException;
import software.coolstuff.springframework.owncloud.exception.resource.OwncloudResourceException;
import software.coolstuff.springframework.owncloud.service.impl.local.OwncloudLocalProperties.ResourceServiceProperties;
import software.coolstuff.springframework.owncloud.service.impl.local.OwncloudLocalProperties.ResourceServiceProperties.MessageDigestAlgorithm;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

@Slf4j
public class OwncloudLocalResourceChecksumServiceImpl implements OwncloudLocalResourceChecksumService {

  @Autowired
  private OwncloudLocalProperties properties;

  private final Map<Path, String> checksums = new ConcurrentHashMap<>();

  private final InitializingFileVisitor fileVisitor;
  private MessageDigest messageDigest;

  public OwncloudLocalResourceChecksumServiceImpl() {
    fileVisitor = InitializingFileVisitor.builder()
                                         .checksums(checksums)
                                         .directoryDigest(this::createDirectoryChecksum)
                                         .fileDigest(this::createFileChecksum)
                                         .build();
  }

  private static class InitializingFileVisitor extends SimpleFileVisitor<Path> {

    private final Function<Path, String> fileDigest;
    private final BiFunction<Path, Map<Path, String>, String> directoryDigest;

    private final Map<Path, String> checksums;

    @Builder
    private InitializingFileVisitor(
        final Function<Path, String> fileDigest,
        final BiFunction<Path, Map<Path, String>, String> directoryDigest,
        final Map<Path, String> checksums) {
      this.fileDigest = fileDigest;
      this.directoryDigest = directoryDigest;
      this.checksums = checksums;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
      checksums.keySet().stream()
               .filter(path -> isSamePath(path.getParent(), dir))
               .forEach(path -> checksums.remove(path.toAbsolutePath().normalize()));
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
      String checksum = fileDigest.apply(file);
      checksums.put(file.toAbsolutePath().normalize(), checksum);
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
      String checksum = directoryDigest.apply(dir, checksums);
      checksums.put(dir.toAbsolutePath().normalize(), checksum);
      return FileVisitResult.CONTINUE;
    }

  }

  @PostConstruct
  public void afterPropertiesSet() throws Exception {
    ResourceServiceProperties resourceProperties = properties.getResourceService();
    OwncloudLocalUtils.checkPrivilegesOnDirectory(resourceProperties.getLocation());
    setMessageDigest(resourceProperties.getMessageDigestAlgorithm());
    log.debug("Calculate the Checksum of all Files and Directories of Directory {}", resourceProperties.getLocation());
    Files.walkFileTree(resourceProperties.getLocation(), getFileVisitor());
  }

  protected final FileVisitor<Path> getFileVisitor() {
    return fileVisitor;
  }

  private void setMessageDigest(MessageDigestAlgorithm messageDigestAlgorithm) throws NoSuchAlgorithmException {
    Validate.notNull(messageDigestAlgorithm);
    messageDigest = messageDigestAlgorithm.getMessageDigest();
  }

  private String createDirectoryChecksum(Path path, Map<Path, String> fileChecksums) {
    log.debug("Calculate the Checksum of Directory {}", path);
    try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
      fileChecksums.entrySet().stream()
                   .filter(entry -> isSamePath(path, entry.getKey().getParent()))
                   .forEach(entry -> writeChecksumEntry(entry.getValue(), stream));
      synchronized (messageDigest) {
        messageDigest.reset();
        messageDigest.update(stream.toByteArray());
        return Hex.encodeHexString(messageDigest.digest());
      }
    } catch (IOException e) {
      val logMessage = String.format("Cannot calculate the Checksum of Directory %s", path);
      log.error(logMessage, e);
      throw new OwncloudLocalResourceChecksumServiceException(logMessage, e);
    }
  }

  private static boolean isSamePath(Path source, Path destination) {
    try {
      return Files.isSameFile(source, destination);
    } catch (IOException e) {
      val logMessage = String.format("Cannot determine the Equality of the Directories %s and %s", source, destination);
      log.error(logMessage, e);
      throw new OwncloudLocalResourceChecksumServiceException(logMessage, e);
    }
  }

  private void writeChecksumEntry(String checksum, ByteArrayOutputStream stream) {
    try {
      stream.write(checksum.getBytes());
    } catch (IOException e) {
      val logMessage = String.format("Cannot add the Checksum %s to the ByteArrayOutputStream", checksum);
      log.error(logMessage, e);
      throw new OwncloudLocalResourceChecksumServiceException(logMessage, e);
    }
  }

  private String createFileChecksum(Path path) {
    log.debug("Calculate the Checksum of File {}", path);
    try (InputStream stream = new BufferedInputStream(new FileInputStream(path.toFile()))) {
      synchronized (messageDigest) {
        messageDigest.reset();
        byte[] buffer = IOUtils.toByteArray(stream);
        messageDigest.update(buffer);
        return Hex.encodeHexString(messageDigest.digest());
      }
    } catch (IOException e) {
      val logMessage = String.format("Cannot calculate the Checksum of File %s", path);
      log.error(logMessage, e);
      throw new OwncloudLocalResourceChecksumServiceException(logMessage, e);
    }
  }

  @Override
  public Optional<String> getChecksum(Path path) throws OwncloudResourceException {
    return Optional.ofNullable(path)
                   .map(p -> checksums.get(p.toAbsolutePath().normalize()));
  }

  @Override
  public void recalculateChecksum(Path path) throws OwncloudResourceException {
    Validate.notNull(path);
    if (Files.notExists(path)) {
      log.debug("Remove the Checksum of {}", path.toAbsolutePath().normalize());
      checksums.remove(path.toAbsolutePath().normalize());
      return;
    }
    if (Files.isDirectory(path)) {
      createDirectoryChecksumRecursively(path);
      return;
    }
    log.debug("Recalculate the Checksum of File {}", path.toAbsolutePath().normalize());
    String checksum = createFileChecksum(path);
    checksums.put(path.toAbsolutePath().normalize(), checksum);
    createDirectoryChecksumRecursively(path.getParent());
  }

  private void createDirectoryChecksumRecursively(Path path) {
    ResourceServiceProperties resourceProperties = properties.getResourceService();
    Path rootDirectory = resourceProperties.getLocation().toAbsolutePath().normalize();
    Path normalizedPath = path.toAbsolutePath().normalize();
    //    if (rootDirectory.equals(normalizedPath)) {
    if (isSamePath(rootDirectory, normalizedPath)) {
      return;
    }
    log.debug("Clean the Checksum of all non-existing Files within Directory {}", normalizedPath);
    checksums.keySet().stream()
             .filter(checksumPath -> isSamePath(checksumPath.getParent(), path))
             .filter(Files::notExists)
             .forEach(checksums::remove);
    String checksum = createDirectoryChecksum(normalizedPath, checksums);
    checksums.put(normalizedPath, checksum);
    createDirectoryChecksumRecursively(normalizedPath.getParent());
  }

  @Override
  public void recalculateChecksums() throws OwncloudResourceException {
    ResourceServiceProperties resourceProperties = properties.getResourceService();
    try {
      Files.walkFileTree(resourceProperties.getLocation(), getFileVisitor());
    } catch (IOException e) {
      val logMessage = String.format("Cannot recalculate the Checksum of all Files and Directories of Directory %s", resourceProperties.getLocation());
      log.error(logMessage, e);
      throw new OwncloudLocalResourceChecksumServiceException(logMessage, e);
    }
  }

}
