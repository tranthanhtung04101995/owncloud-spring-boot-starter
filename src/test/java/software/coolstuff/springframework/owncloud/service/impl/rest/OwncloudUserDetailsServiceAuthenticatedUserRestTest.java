/*
   Copyright (C) 2016 by the original Authors.

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

import static org.springframework.http.HttpMethod.GET;

import java.io.IOException;
import java.net.MalformedURLException;

import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.ActiveProfiles;

import software.coolstuff.springframework.owncloud.exception.OwncloudInvalidAuthenticationObjectException;

@ActiveProfiles("AUTHENTICATED-USER-URL")
public class OwncloudUserDetailsServiceAuthenticatedUserRestTest extends AbstractOwncloudUserDetailsServiceRestTest {

  @Autowired
  private UserDetailsService userDetailsService;

  @Override
  public final String getBasicAuthorizationHeader() {
    return getSecurityContextBasicAuthorizationHeader();
  }

  @Override
  protected Class<? extends UserDetailsService> getUserDetailsServiceClass() {
    return OwncloudRestUserDetailsService.class;
  }

  @Test(expected = OwncloudInvalidAuthenticationObjectException.class)
  @WithAnonymousUser
  public void testUserDetails_WrongAuthenticationObject() throws MalformedURLException, IOException {
    respondUser(
        RestRequest.builder()
            .method(GET)
            .url("/cloud/user/user1")
            .build(),
        true,
        "user1@example.com",
        "Mr. User 1");

    userDetailsService.loadUserByUsername("user1");
  }

}