package software.coolstuff.springframework.owncloud.service.impl;

import static org.springframework.http.HttpMethod.GET;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import software.coolstuff.springframework.owncloud.service.AbstractOwncloudUserDetailsServiceWithAuthorityAppenderTest;

@ActiveProfiles("AUTHORITY-APPENDER-URL")
public class OwncloudUserDetailsServiceWithAuthorityAppenderRestTest extends AbstractOwncloudUserDetailsServiceWithAuthorityAppenderTest implements OwncloudServiceRestTest {

  @Autowired
  private OwncloudUserDetailsService userDetailsService;

  @Override
  public AbstractOwncloudServiceImpl owncloudService() {
    return userDetailsService;
  }

  @Override
  public String getBasicAuthorizationHeader() {
    return getSecurityContextBasicAuthorizationHeader();
  }

  @Override
  protected void prepareTestAppendedGroups(String username, boolean enabled, String email, String displayName, String... groups) throws Exception {
    respondUser(
        RestRequest.builder()
            .method(GET)
            .url("/cloud/users/" + username)
            .build(),
        enabled,
        email,
        displayName);
    respondGroups(
        RestRequest.builder()
            .method(GET)
            .url("/cloud/users/" + username + "/groups")
            .build(),
        groups);
  }
}
