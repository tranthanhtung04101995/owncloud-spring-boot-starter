package software.coolstuff.springframework.owncloud.service.impl;

import static org.springframework.http.HttpMethod.GET;

import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import software.coolstuff.springframework.owncloud.config.WithOwncloudMockUser;
import software.coolstuff.springframework.owncloud.service.AbstractOwncloudUserDetailsServiceTest;

public abstract class AbstractOwncloudUserDetailsServiceRestTest extends AbstractOwncloudUserDetailsServiceTest implements OwncloudServiceRestTest {

  @Autowired
  private OwncloudUserDetailsService userDetailsService;

  @Override
  public final OwncloudUserDetailsService owncloudService() {
    return userDetailsService;
  }

  @Override
  protected void prepareTestUserDetails_OK(String user, boolean enabled, String email, String displayName, String... groups) throws Exception {
    respondUser(
        RestRequest.builder()
            .method(GET)
            .url("/cloud/users/" + user)
            .build(),
        enabled,
        email,
        displayName);
    respondGroups(
        RestRequest.builder()
            .method(GET)
            .url("/cloud/users/" + user + "/groups")
            .build(),
        groups);
  }

  @Override
  protected void prepareTestUserDetails_NotFound() throws Exception {
    respondFailure(
        RestRequest.builder()
            .method(GET)
            .url("/cloud/users/unknown")
            .build(),
        998,
        "The requested user could not be found");
  }

  @Test(expected = AccessDeniedException.class)
  @WithOwncloudMockUser(username = "user1", password = "password")
  public void testUserDetails_AccessDenied() throws Exception {
    respondFailure(
        RestRequest.builder()
            .method(GET)
            .url("/cloud/users/user1")
            .build(),
        997);

    userDetailsService.loadUserByUsername("user1");
  }

  @Test(expected = IllegalStateException.class)
  @WithOwncloudMockUser(username = "user1", password = "password")
  public void testUserDetails_UnknownError() throws Exception {
    respondFailure(
        RestRequest.builder()
            .method(GET)
            .url("/cloud/users/user1")
            .build(),
        999);

    userDetailsService.loadUserByUsername("user1");
  }

}
