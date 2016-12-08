package software.coolstuff.springframework.owncloud.service.impl;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.SpringBootDependencyInjectionTestExecutionListener;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.UrlResource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextTestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Diff;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import software.coolstuff.springframework.owncloud.config.CompareResourceAfter;
import software.coolstuff.springframework.owncloud.config.OwncloudResourceFileTest;
import software.coolstuff.springframework.owncloud.config.OwncloudResourceFileTestExecutionListener;
import software.coolstuff.springframework.owncloud.properties.OwncloudProperties;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = { OwncloudAutoConfiguration.class }, webEnvironment = WebEnvironment.NONE)
@TestExecutionListeners({
    SpringBootDependencyInjectionTestExecutionListener.class,
    DependencyInjectionTestExecutionListener.class,
    WithSecurityContextTestExecutionListener.class,
    OwncloudResourceFileTestExecutionListener.class
})
@Slf4j
public abstract class AbstractOwncloudServiceTest {

  private final static String ORIGINAL_RESOURCE = "classpath:/owncloud.xml";

  @Autowired
  private ResourceLoader resourceLoader;

  @Autowired(required = false)
  private GrantedAuthoritiesMapper grantedAuthoritiesMapper;

  @Autowired
  private OwncloudProperties properties;

  @Rule
  public TestName testName = new TestName();

  @Autowired(required = false)
  private OwncloudResourceService resourceService;

  private MockRestServiceServer server;

  @Before
  public final void setUp() throws Exception {
    if (this instanceof OwncloudServiceRestTest) {
      server = createServer(((OwncloudServiceRestTest) this).owncloudService());
    }

    if (this instanceof OwncloudResourceFileTest) {
      copyClasspathResourceToFile();
      resourceService.afterPropertiesSet();
    }
  }

  protected final MockRestServiceServer createServer(AbstractOwncloudServiceImpl owncloudService) {
    return MockRestServiceServer.createServer(owncloudService.getRestTemplate());
  }

  protected void verifyServer() {
    if (server != null) {
      server.verify();
    }
  }

  protected final MockRestServiceServer getServer() {
    return server;
  }

  private void copyClasspathResourceToFile() throws IOException, FileNotFoundException {
    Resource target = resourceLoader.getResource(properties.getLocation());
    if (!(target instanceof UrlResource)) {
      throw new IllegalStateException(String.format("TestClass %s implements %s but the Resource-Location %s is not of Type %s", this.getClass().getName(),
          OwncloudResourceFileTest.class.getName(), properties.getLocation(), UrlResource.class.getName()));
    }

    try (InputStream is = new BufferedInputStream(getSourceResource().getInputStream());
        OutputStream os = new BufferedOutputStream(new FileOutputStream(target.getFile()))) {
      log.debug("Copy Content of Classpath-Resource {} to File {}", ORIGINAL_RESOURCE, properties.getLocation());
      IOUtils.copy(is, os);
    }
  }

  @After
  public void tearDownResource() throws Throwable {
    if (this instanceof OwncloudResourceFileTest) {
      resourceService.destroy();
      Resource target = resourceLoader.getResource(properties.getLocation());

      boolean hasSpecificResourceTest = false;
      for (Method method : this.getClass().getMethods()) {
        // is this Method annotated by @CompareResourceAfter
        CompareResourceAfter compareResourceAfter = AnnotationUtils.findAnnotation(method, CompareResourceAfter.class);
        if (compareResourceAfter == null || !StringUtils.equals(compareResourceAfter.value(), testName.getMethodName())) {
          continue;
        }

        // a Method annotated by @Test cannot also be annotated by @CompareResourceAfter
        if (AnnotationUtils.findAnnotation(method, Test.class) != null) {
          log.warn("Method {} of Class {} cannot be annotated by {} and {}", method.getName(), this.getClass().getName(), CompareResourceAfter.class, Test.class);
          continue;
        }

        // the @CompareResourceAfter annotated Method must have exactly 2 Parameters of Type org.springframework.core.io.Resource
        if (method.getParameterCount() != 1) {
          log.warn("Method {} of Class {} is annotated by {} but has {} Parameters instead of 1",
              method.getName(), this.getClass().getName(), CompareResourceAfter.class.getName(), method.getParameterCount());
          continue;
        }
        boolean correctParameterTypes = true;
        for (Class<?> parameterClass : method.getParameterTypes()) {
          correctParameterTypes = correctParameterTypes && Resource.class.isAssignableFrom(parameterClass);
        }
        if (!correctParameterTypes) {
          log.warn("Method {} of Class {} (annotated by {}) must have 1 Parameter of Type {}",
              method.getName(), this.getClass(), CompareResourceAfter.class.getName(), Resource.class.getName());
          continue;
        }

        log.debug("Call the Resource Comparsion Method {} on Class {}", method.getName(), this.getClass().getName());
        hasSpecificResourceTest = true;
        try {
          method.invoke(this, target);
        } catch (InvocationTargetException e) {
          throw e.getCause();
        }
      }

      if (!hasSpecificResourceTest && ((OwncloudResourceFileTest) this).isCheckAllResourcesAgainstOriginal()) {
        compareResourcesWithOriginalSource(target);
      }
    }
  }

  protected String getResponseContentOf(String testCase) throws IOException {
    Resource resource = getResourceOf(testCase);
    return IOUtils.toString(resource.getInputStream());
  }

  protected Resource getResourceOf(String testCase) {
    String path = "/";
    if (StringUtils.isNotBlank(getResourcePrefix())) {
      if (StringUtils.startsWith(getResourcePrefix(), "/")) {
        path = StringUtils.appendIfMissing(getResourcePrefix(), "/");
      } else {
        path += StringUtils.appendIfMissing(getResourcePrefix(), "/");
      }
    }

    return resourceLoader.getResource("classpath:" + path + testCase + ".xml");
  }

  protected abstract String getResourcePrefix();

  protected void checkAuthorities(Collection<? extends GrantedAuthority> actual, String... expected) {
    Assert.assertEquals(expected.length, actual == null ? 0 : actual.size());
    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
    if (ArrayUtils.isNotEmpty(expected)) {
      for (String authority : expected) {
        authorities.add(new SimpleGrantedAuthority(authority));
      }
    }
    if (grantedAuthoritiesMapper != null) {
      Assert.assertTrue(CollectionUtils.isEqualCollection(actual, grantedAuthoritiesMapper.mapAuthorities(authorities)));
    } else {
      Assert.assertTrue(CollectionUtils.isEqualCollection(actual, authorities));
    }
  }

  protected Resource getSourceResource() {
    Resource resource = resourceLoader.getResource(ORIGINAL_RESOURCE);
    if (resource == null) {
      throw new IllegalStateException("Source Resource " + ORIGINAL_RESOURCE + " is not available");
    }
    return resource;
  }

  protected void compareResourcesWithOriginalSource(Resource target) throws Exception {
    compareResources(getSourceResource(), target);
  }

  protected void compareResources(Resource source, Resource target) throws Exception {
    try (InputStream isSource = new BufferedInputStream(source.getInputStream());
        InputStream isTarget = new BufferedInputStream(target.getInputStream())) {
      Diff diff = DiffBuilder.compare(Input.fromStream(isSource))
          .withTest(Input.fromStream(isTarget))
          .build();
      Assert.assertFalse(diff.toString(), diff.hasDifferences());
    }
  }

  protected final RequestMatcher requestToWithPrefix(String uri) throws MalformedURLException {
    String rootURI = null;
    if (OwncloudResourceService.isNoResource(properties.getLocation())) {
      URL url = new URL(properties.getLocation());
      rootURI = properties.getLocation();
      if (StringUtils.isBlank(url.getPath()) || "/".equals(url.getPath())) {
        rootURI = URI.create(url.toString() + AbstractOwncloudServiceImpl.DEFAULT_PATH).toString();
      }
    }
    return requestTo(rootURI + uri);
  }

  protected final String getDefaultBasicAuthorizationHeader() {
    return "Basic "
        + Base64.getEncoder().encodeToString((properties.getUsername() + ":" + properties.getPassword()).getBytes());
  }

  protected final String getSecurityContextBasicAuthorizationHeader() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return "Basic " + Base64.getEncoder()
        .encodeToString((authentication.getName() + ":" + authentication.getCredentials()).getBytes());
  }

  @Data
  @Builder
  protected static class Credentials {

    private final String username;
    private final String password;

    public String getForBasicAuthorizationHeader() {
      return "Basic " + Base64.getEncoder().encodeToString((getUsername() + ":" + getPassword()).getBytes());
    }
  }

}
