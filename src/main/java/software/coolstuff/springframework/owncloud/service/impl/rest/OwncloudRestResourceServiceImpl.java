/*
   Copyright (C) 2017 by the original Authors.

   This program is free software; you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation; either version 3 of the License, or
   (at your option) any later version.

   This program is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.

   You should have received a copy of the GNU General Public License
   along with this program; if not, write to the Free Software Foundation,
   Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301  USA
*/
package software.coolstuff.springframework.owncloud.service.impl.rest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.github.sardine.DavResource;
import com.github.sardine.Sardine;
import com.github.sardine.impl.SardineException;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.val;
import lombok.extern.slf4j.Slf4j;
import software.coolstuff.springframework.owncloud.exception.resource.*;
import software.coolstuff.springframework.owncloud.model.OwncloudFileResource;
import software.coolstuff.springframework.owncloud.model.OwncloudQuota;
import software.coolstuff.springframework.owncloud.model.OwncloudResource;
import software.coolstuff.springframework.owncloud.service.api.OwncloudResourceService;
import software.coolstuff.springframework.owncloud.service.impl.OwncloudUtils;
import software.coolstuff.springframework.owncloud.service.impl.rest.OwncloudRestProperties.ResourceServiceProperties.CacheProperties;

@Slf4j
class OwncloudRestResourceServiceImpl implements OwncloudResourceService, OwncloudRestService, OwncloudResolveRootUriService {

  private static final String URI_SUFFIX = "/remote.php/dav/files/{username}/";

  private final RestTemplate restOperations;
  private final OwncloudRestProperties properties;
  private final String rootUri;

  private LoadingCache<String, Sardine> sardineCache;

  @Autowired
  private SardineCacheLoader sardineCacheLoader;

  @Autowired
  private OwncloudRestUserQueryService userQueryService;

  public OwncloudRestResourceServiceImpl(
      final RestTemplateBuilder builder,
      final OwncloudRestProperties properties) throws MalformedURLException {
    this.properties = properties;
    URL locationURL = OwncloudRestUtils.checkAndConvertLocation(properties.getLocation());
    this.rootUri = appendOptionalSuffix(locationURL, URI_SUFFIX);
    HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory();
    log.debug("Build the RestTemplate");
    restOperations = builder
        .requestFactory(requestFactory)
        .messageConverters(new ByteArrayHttpMessageConverter())
        .rootUri(rootUri)
        .build();
  }

  protected String appendOptionalSuffix(URL url, String suffix) {
    if (StringUtils.isBlank(suffix)) {
      return url.toString();
    }
    return StringUtils.stripEnd(url.toString(), "/") + "/" + StringUtils.stripStart(suffix, "/");
  }

  @Override
  public RestTemplate getRestTemplate() {
    return restOperations;
  }

  @PostConstruct
  public void afterPropertiesSet() throws Exception {
    log.debug("Build the Sardine Cache");
    this.sardineCache = buildSardineCache();
  }

  protected LoadingCache<String, Sardine> buildSardineCache() {
    CacheProperties cacheProperties = properties.getResourceService().getCache();
    CacheBuilder<Object, Object> builder = CacheBuilder.newBuilder();
    if (cacheProperties.getConcurrencyLevel() != null) {
      builder.concurrencyLevel(cacheProperties.getConcurrencyLevel());
    }
    if (cacheProperties.getExpireAfterAccess() != null && cacheProperties.getExpireAfterAccessTimeUnit() != null) {
      builder.expireAfterAccess(cacheProperties.getExpireAfterAccess(), cacheProperties.getExpireAfterAccessTimeUnit());
    }
    if (cacheProperties.getExpireAfterWrite() != null && cacheProperties.getExpireAfterWriteTimeUnit() != null) {
      builder.expireAfterWrite(cacheProperties.getExpireAfterWrite(), cacheProperties.getExpireAfterWriteTimeUnit());
    }
    if (cacheProperties.getInitialCapacity() != null) {
      builder.initialCapacity(cacheProperties.getInitialCapacity());
    }
    if (cacheProperties.getMaximumSize() != null) {
      builder.maximumSize(cacheProperties.getMaximumSize());
    }
    if (cacheProperties.getMaximumWeight() != null) {
      builder.maximumWeight(cacheProperties.getMaximumWeight());
    }
    if (cacheProperties.getRefreshAfterWrite() != null && cacheProperties.getRefreshAfterWriteTimeUnit() != null) {
      builder.refreshAfterWrite(cacheProperties.getRefreshAfterWrite(), cacheProperties.getRefreshAfterWriteTimeUnit());
    }
    return builder.build(sardineCacheLoader);
  }

  @Override
  public List<OwncloudResource> list(URI relativeTo) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    URI searchPath = resolveAsDirectoryURI(relativeTo, authentication.getName());
    try {
      return listAllOwncloudResourcesOf(searchPath);
    } catch (SardineException e) {
      SardineExceptionHandlerEnvironment handlerEnvironment = SardineExceptionHandlerEnvironment.builder()
          .uri(URI.create(searchPath.toString()))
          .authentication(authentication)
          .sardineException(e)
          .build();
      registerDefaultStatusCodeHandler(handlerEnvironment);
      handleSardineException(handlerEnvironment);
      return new ArrayList<>();
    } catch (IOException e) {
      throw new OwncloudRestResourceException(e);
    }
  }

  public URI resolveAsDirectoryURI(URI relativeTo, String username) {
    URI resolvedRootUri = getResolvedRootUri(username);
    if (relativeTo == null || StringUtils.isBlank(relativeTo.getPath())) {
      return resolvedRootUri;
    }
    return URI.create(
        UriComponentsBuilder.fromUri(resolvedRootUri)
            .path(relativeTo.getPath())
            .path("/")
            .toUriString())
        .normalize();
  }

  @Override
  public URI getResolvedRootUri(String username) {
    log.debug("Get base URI of User {} (under Root URI {})", username, rootUri);
    return URI.create(StringUtils.replace(rootUri, "{username}", username));
  }

  private List<OwncloudResource> listAllOwncloudResourcesOf(URI searchPath) throws IOException {
    List<OwncloudResource> owncloudResources = new ArrayList<>();
    listOwncloudResourcesOf(searchPath)
        .peek(owncloudResource -> log.debug("Add Owncloud Resource {}", owncloudResource))
        .forEach(owncloudResources::add);
    if (isAddParentResourceToCollection(searchPath, owncloudResources)) {
      listParentOwncloudResourcesOf(searchPath)
          .peek(owncloudResource -> log.debug("Add Owncloud Resource {}", owncloudResource))
          .forEach(owncloudResources::add);
    }
    return owncloudResources;
  }

  private Stream<OwncloudResource> listOwncloudResourcesOf(URI searchPath) throws IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    Sardine sardine = getSardine();
    URI rootPath = getResolvedRootUri(authentication.getName());
    log.debug("Get the List of WebDAV Resources based by URI {}", searchPath);
    List<DavResource> davResources = sardine.list(searchPath.toString());
    val searchPathConversionProperties = OwncloudResourceConversionProperties.builder()
        .rootPath(rootPath)
        .searchPath(searchPath)
        .renamedSearchPath(".")
        .build();
    return davResources.stream()
        .map(davResource -> createOwncloudResourceFrom(davResource, searchPathConversionProperties))
        .map(modifyingResource -> renameOwncloudResource(modifyingResource, searchPathConversionProperties));
  }

  protected Sardine getSardine() throws OwncloudSardineCacheException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    String username = authentication.getName();
    try {
      log.debug("Get the Sardine Implementation of User {}", username);
      return sardineCache.get(username);
    } catch (Exception e) {
      val logMessage = String.format("Cannot get the Sardine Implementation based by User %s from the Sardine Cache", username);
      log.error(logMessage, e);
      throw new OwncloudSardineCacheException(logMessage, e);
    }
  }

  @Data
  @Builder
  static class OwncloudResourceConversionProperties {

    private final URI rootPath;
    private URI searchPath;
    private String renamedSearchPath;
  }

  public OwncloudModifyingRestResource createOwncloudResourceFrom(DavResource davResource, OwncloudResourceConversionProperties conversionProperties) {
    log.debug("Create OwncloudResource based on DavResource {}", davResource.getHref());
    MediaType mediaType = MediaType.valueOf(davResource.getContentType());
    URI rootPath = conversionProperties.getRootPath();
    URI href = rootPath.resolve(davResource.getHref());
    String name = davResource.getName();
    if (davResource.isDirectory() && href.equals(rootPath)) {
      name = "/";
    }
    LocalDateTime lastModifiedAt = LocalDateTime.ofInstant(davResource.getModified().toInstant(), ZoneId.systemDefault());
    href = rootPath.relativize(href);
    href = URI.create("/").resolve(href).normalize(); // prepend "/" to the href
    OwncloudModifyingRestResource owncloudResource = OwncloudRestResourceImpl.builder()
        .href(href)
        .name(name)
        .lastModifiedAt(lastModifiedAt)
        .mediaType(mediaType)
        .eTag(StringUtils.strip(davResource.getEtag(), "\""))
        .build();
    if (davResource.isDirectory()) {
      return owncloudResource;
    }
    return OwncloudRestFileResourceImpl.fileBuilder()
        .owncloudResource(owncloudResource)
        .contentLength(davResource.getContentLength())
        .build();
  }

  public OwncloudModifyingRestResource renameOwncloudResource(OwncloudModifyingRestResource resource, OwncloudResourceConversionProperties conversionProperties) {
    if (StringUtils.isBlank(conversionProperties.getRenamedSearchPath())) {
      return resource;
    }
    URI resourcePath = URI.create(
        UriComponentsBuilder.fromUri(conversionProperties.getRootPath())
            .path(resource.getHref().getPath())
            .toUriString())
        .normalize();
    if (conversionProperties.getSearchPath().equals(resourcePath)) {
      log.debug("Rename OwncloudResource {} based by {} to {}", resource.getName(), resource.getHref(), conversionProperties.getRenamedSearchPath());
      resource.setName(conversionProperties.getRenamedSearchPath());
    }
    return resource;
  }

  private boolean isAddParentResourceToCollection(URI searchPath, List<OwncloudResource> owncloudResources) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return properties.getResourceService().isAddRelativeDownPath()
        && isNotResolvedToRootURI(searchPath, authentication.getName())
        && containsNotOnlyOneFileResource(owncloudResources);
  }

  private boolean isNotResolvedToRootURI(URI path, String username) {
    return !isResolvedToRootURI(path, username);
  }

  private boolean isResolvedToRootURI(URI path, String username) {
    URI resolvedRootUri = getResolvedRootUri(username);
    if (path.isAbsolute()) {
      return resolvedRootUri.equals(path);
    }
    return resolvedRootUri.equals(resolveAsDirectoryURI(path, username));
  }

  private boolean containsNotOnlyOneFileResource(List<OwncloudResource> owncloudResources) {
    return !containsOnlyOneFileResource(owncloudResources);
  }

  private boolean containsOnlyOneFileResource(List<OwncloudResource> owncloudResources) {
    return owncloudResources.size() == 1 && !OwncloudUtils.isDirectory(owncloudResources.get(0));
  }

  private Stream<OwncloudResource> listParentOwncloudResourcesOf(URI searchPath) throws IOException {
    URI parentPath = URI.create(
        UriComponentsBuilder.fromUri(searchPath.normalize())
            .path("/../")
            .toUriString())
        .normalize();
    log.debug("Get the List of WebDAV Resources based by Parent URI {}", parentPath);
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    URI rootPath = getResolvedRootUri(authentication.getName());
    val parentDirectoryConversionProperties = OwncloudResourceConversionProperties.builder()
        .rootPath(rootPath)
        .searchPath(parentPath)
        .renamedSearchPath("..")
        .build();
    Sardine sardine = getSardine();
    List<DavResource> davResources = sardine.list(parentPath.toString(), 0);
    return davResources.stream()
        .map(davResource -> createOwncloudResourceFrom(davResource, parentDirectoryConversionProperties))
        .map(modifyingResource -> renameOwncloudResource(modifyingResource, parentDirectoryConversionProperties));
  }

  private static class SardineExceptionHandlerEnvironment {
    @Getter
    private final URI uri;
    private final Authentication authentication;
    @Getter
    private final SardineException sardineException;

    private final Map<Integer, Function<SardineExceptionHandlerEnvironment, Optional<OwncloudResource>>> statusCodeHandler = new HashMap<>();

    @Builder
    private SardineExceptionHandlerEnvironment(
        final URI uri,
        final Authentication authentication,
        final SardineException sardineException) {
      Validate.notNull(uri);
      Validate.notNull(sardineException);
      this.uri = uri;
      this.authentication = authentication;
      this.sardineException = sardineException;
    }

    public String getUsername() {
      return authentication == null ? null : authentication.getName();
    }

    void registerStatusCodeHandler(int statusCode, Function<SardineExceptionHandlerEnvironment, Optional<OwncloudResource>> statusCodeHandler) {
      this.statusCodeHandler.put(statusCode, statusCodeHandler);
    }

    Function<SardineExceptionHandlerEnvironment, Optional<OwncloudResource>> getStatusCodeHandlerFor(int statusCode) {
      return statusCodeHandler.get(statusCode);
    }
  }

  private void registerDefaultStatusCodeHandler(SardineExceptionHandlerEnvironment environment) {
    environment.registerStatusCodeHandler(HttpStatus.SC_NOT_FOUND, this::handleStatusCodeNotFound);
  }

  private Optional<OwncloudResource> handleStatusCodeNotFound(SardineExceptionHandlerEnvironment environment) {
    throw new OwncloudResourceNotFoundException(environment.getUri(), environment.getUsername());
  }

  private Optional<OwncloudResource> handleSardineException(SardineExceptionHandlerEnvironment environment) {
    SardineException sardineException = environment.getSardineException();
    int statusCode = sardineException.getStatusCode();
    Function<SardineExceptionHandlerEnvironment, Optional<OwncloudResource>> statusCodeHandler = environment.getStatusCodeHandlerFor(statusCode);
    if (statusCodeHandler != null) {
      return statusCodeHandler.apply(environment);
    }
    log.error("Unmapped HTTP-Status {}. Reason-Phrase: {}", statusCode, sardineException.getResponsePhrase());
    throw new OwncloudRestResourceException("Unmapped returned HTTP-Status " + statusCode, sardineException);
  }

  @Override
  public Optional<OwncloudResource> find(URI path) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    URI searchPath = resolveAsDirectoryURI(path, authentication.getName());
    val conversionProperties = OwncloudResourceConversionProperties.builder()
        .rootPath(getResolvedRootUri(authentication.getName()))
        .searchPath(searchPath)
        .build();
    try {
      Sardine sardine = getSardine();
      List<DavResource> davResources = sardine.list(searchPath.toString(), 0);
      return Optional.ofNullable(
          davResources.stream()
              .findFirst()
              .map(davResource -> createOwncloudResourceFrom(davResource, conversionProperties))
              .orElse(null));
    } catch (SardineException e) {
      SardineExceptionHandlerEnvironment handlerEnvironment = SardineExceptionHandlerEnvironment.builder()
          .uri(URI.create(searchPath.toString()))
          .authentication(authentication)
          .sardineException(e)
          .build();
      registerDefaultStatusCodeHandler(handlerEnvironment);
      handlerEnvironment.registerStatusCodeHandler(HttpStatus.SC_NOT_FOUND, environment -> Optional.empty());
      return handleSardineException(handlerEnvironment);
    } catch (IOException e) {
      throw new OwncloudRestResourceException(e);
    }
  }

  @Override
  public OwncloudResource createDirectory(URI directory) {
    Optional<OwncloudResource> existingDirectory = find(directory);
    if (existingDirectory.isPresent()) {
      return existingDirectory
          .filter(OwncloudUtils::isDirectory)
          .orElseThrow(() -> new OwncloudNoDirectoryResourceException(directory));
    }
    return createNonExistingDirectory(directory);
  }

  private OwncloudResource createNonExistingDirectory(URI directory) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    URI directoryURI = resolveAsDirectoryURI(directory, authentication.getName());
    OwncloudResource owncloudResource = null;
    try {
      Sardine sardine = getSardine();
      sardine.createDirectory(directoryURI.toString());
      owncloudResource = find(directory).get();
    } catch (SardineException e) {
      SardineExceptionHandlerEnvironment handlerEnvironment = SardineExceptionHandlerEnvironment.builder()
          .uri(URI.create(directory.toString()))
          .authentication(authentication)
          .sardineException(e)
          .build();
      registerDefaultStatusCodeHandler(handlerEnvironment);
      handleSardineException(handlerEnvironment);
    } catch (IOException e) {
      throw new OwncloudRestResourceException(e);
    }
    return owncloudResource;
  }

  @Override
  public void delete(OwncloudResource resource) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    URI resolvedUri = resolveAsFileURI(resource.getHref(), authentication.getName());
    try {
      restOperations.execute(resolvedUri, HttpMethod.DELETE, clientHttpRequest -> createRestCallback(clientHttpRequest, authentication), null);
    } catch (RestClientException restClientException) {
      RestClientExceptionHandlerEnvironment exceptionHandlerEnvironment = RestClientExceptionHandlerEnvironment.builder()
          .restClientException(restClientException)
          .requestURI(resource.getHref())
          .username(authentication.getName())
          .build();
      OwncloudRestUtils.handleRestClientException(exceptionHandlerEnvironment);
    }
  }

  private URI resolveAsFileURI(URI relativeTo, String username) {
    URI resolvedRootUri = getResolvedRootUri(username);
    if (relativeTo == null || StringUtils.isBlank(relativeTo.getPath())) {
      return resolvedRootUri;
    }
    return URI.create(
        UriComponentsBuilder.fromUri(resolvedRootUri)
            .path(relativeTo.getPath())
            .toUriString())
        .normalize();
  }

  private void createRestCallback(ClientHttpRequest clientHttpRequest, Authentication authentication) throws IOException {
    OwncloudRestUtils.addAuthorizationHeader(clientHttpRequest.getHeaders(), authentication);
  }

  @Override
  public InputStream getInputStream(OwncloudFileResource resource) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    PipedInputStreamRestSynchronizer pipedInputStreamSynchronizer = PipedInputStreamRestSynchronizer.build()
        .authentication(authentication)
        .owncloudRestProperties(properties)
        .restOperations(restOperations)
        .uri(resource.getHref())
        .uriResolver(this::resolveAsFileURI)
        .build();
    return pipedInputStreamSynchronizer.getInputStream();
  }

  @Override
  public OutputStream getOutputStream(OwncloudFileResource resource) {
    Validate.notNull(resource);
    Validate.notNull(resource.getHref());
    Validate.notNull(resource.getMediaType());
    if (OwncloudUtils.isDirectory(resource)) {
      throw new OwncloudNoFileResourceException(resource.getHref());
    }
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    PipedOutputStreamRestSynchronizer pipedOutputStreamSynchronizer = PipedOutputStreamRestSynchronizer.builder()
        .authentication(authentication)
        .mediaType(resource.getMediaType())
        .owncloudRestProperties(properties)
        .restOperations(restOperations)
        .uri(resource.getHref())
        .uriResolver(this::resolveAsFileURI)
        .build();
    return pipedOutputStreamSynchronizer.getOutputStream();
  }

  @Override
  public OutputStream getOutputStream(URI path, MediaType mediaType) {
    Optional<OwncloudResource> optionalExistingFile = find(path);
    if (optionalExistingFile.isPresent()) {
      OwncloudFileResource existingFile = optionalExistingFile
          .filter(OwncloudUtils::isNotDirectory)
          .map(OwncloudUtils::toOwncloudFileResource)
          .orElseThrow(() -> new OwncloudNoFileResourceException(path));
      return getOutputStream(existingFile);
    }
    OwncloudFileResource resource = OwncloudRestFileResourceImpl.fileBuilder()
        .owncloudResource(
            OwncloudRestResourceImpl.builder()
                .href(path)
                .mediaType(mediaType)
                .build())
        .build();
    return getOutputStream(resource);
  }

  @Override
  public OwncloudQuota getQuota() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return userQueryService.getQuota(authentication.getName());
  }
}
