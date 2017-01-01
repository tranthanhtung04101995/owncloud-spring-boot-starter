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
package software.coolstuff.springframework.owncloud.service.impl;

import java.io.IOException;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.SpringSecurityMessageSource;
import org.springframework.security.core.context.SecurityContextHolder;

import software.coolstuff.springframework.owncloud.exception.OwncloudStatusException;
import software.coolstuff.springframework.owncloud.model.OwncloudAuthentication;
import software.coolstuff.springframework.owncloud.model.OwncloudUserDetails;

class OwncloudRestAuthenticationProvider extends AbstractOwncloudRestServiceImpl implements AuthenticationProvider {

  @Autowired
  private OwncloudUserDetailsRestService userDetailsService;

  public OwncloudRestAuthenticationProvider(RestTemplateBuilder builder) {
    super(builder, false, new OwncloudAuthenticationProviderResponseErrorHandler(SpringSecurityMessageSource.getAccessor()));
  }

  @Override
  public Authentication authenticate(Authentication authentication) throws AuthenticationException {

    if (StringUtils.isBlank(authentication.getName())) {
      throw new BadCredentialsException(messages.getMessage("AbstractUserDetailsAuthenticationProvider.badCredentials", "Bad Credentials"));
    }

    if (authentication.getCredentials() == null) {
      throw new BadCredentialsException(messages.getMessage("AbstractUserDetailsAuthenticationProvider.badCredentials", "Bad Credentials"));
    }

    String username = authentication.getName();
    String password = authentication.getCredentials().toString();

    Ocs.User user = exchange("/cloud/users/{user}", HttpMethod.GET, emptyEntity(username, password), Ocs.User.class, username);
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(username, password));

    OwncloudUserDetails owncloudUserDetails = userDetailsService.loadPreloadedUserByUsername(username, user);
    owncloudUserDetails.setPassword(password);

    return new OwncloudAuthentication(owncloudUserDetails);
  }

  @Override
  protected void checkFailure(String uri, Ocs.Meta metaInformation) throws OwncloudStatusException {
    if ("ok".equals(metaInformation.getStatus())) {
      return;
    }
    throw new BadCredentialsException(messages.getMessage("AbstractUserDetailsAuthenticationProvider.badCredentials", "Bad Credentials"));
  }

  @Override
  public boolean supports(Class<?> authentication) {
    return OwncloudUtils.isAuthenticationClassSupported(authentication);
  }

  private static class OwncloudAuthenticationProviderResponseErrorHandler extends DefaultOwncloudResponseErrorHandler {

    public OwncloudAuthenticationProviderResponseErrorHandler(MessageSourceAccessor messages) {
      super(messages);
    }

    @Override
    public void handleError(ClientHttpResponse response) throws IOException {
      HttpStatus statusCode = response.getStatusCode();
      if (HttpStatus.UNAUTHORIZED.compareTo(statusCode) == 0) {
        throw new BadCredentialsException(messages.getMessage("AbstractUserDetailsAuthenticationProvider.badCredentials", "Bad Credentials"));
      }
      super.handleError(response);
    }

  }
}