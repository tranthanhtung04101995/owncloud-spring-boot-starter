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
package software.coolstuff.springframework.owncloud.service.impl.rest;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import software.coolstuff.springframework.owncloud.exception.OwncloudStatusException;
import software.coolstuff.springframework.owncloud.model.OwncloudUserDetails;
import software.coolstuff.springframework.owncloud.service.impl.OwncloudGrantedAuthoritiesMappingService;
import software.coolstuff.springframework.owncloud.service.impl.OwncloudUtils;

@Slf4j
public class OwncloudRestAuthenticationProviderImpl extends AbstractOwncloudRestServiceImpl implements AuthenticationProvider {

  private final OwncloudRestUserDetailsService userDetailsService;
  private final OwncloudGrantedAuthoritiesMappingService grantedAuthoritiesMappingService;

  public OwncloudRestAuthenticationProviderImpl(
      RestTemplateBuilder builder,
      OwncloudRestProperties properties,
      OwncloudRestUserDetailsService userDetailsService,
      OwncloudGrantedAuthoritiesMappingService grantedAuthoritiesMappingService) {
    super(builder, properties);
    this.userDetailsService = userDetailsService;
    this.grantedAuthoritiesMappingService = grantedAuthoritiesMappingService;
  }

  @Override
  public Authentication authenticate(Authentication authentication) throws AuthenticationException {
    String username = authentication.getName();
    if (StringUtils.isBlank(username)) {
      log.warn("Username is null or empty");
      throw new BadCredentialsException(messages.getMessage("AbstractUserDetailsAuthenticationProvider.badCredentials", "Bad Credentials"));
    }

    String password = authentication.getCredentials() != null ? authentication.getCredentials().toString() : null;
    if (StringUtils.isBlank(password)) {
      log.warn("Password is null or empty");
      throw new BadCredentialsException(messages.getMessage("AbstractUserDetailsAuthenticationProvider.badCredentials", "Bad Credentials"));
    }

    log.debug("Try to get Information about User {} from Location {}", username, getLocation());
    Ocs.User user = exchange("/cloud/users/{user}", HttpMethod.GET, emptyEntity(username, password), Ocs.User.class, username);
    if (!user.getData().isEnabled()) {
      log.error("User {} is disabled", username);
      throw new DisabledException(messages.getMessage("AbstractUserDetailsAuthenticationProvider.disabled", "Disabled"));
    }

    log.debug("Set a new UsernamePasswordAuthenticationToken with User {} to the SecurityContextHolder", username);
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(username, password));

    log.info("User {} has been successfully authenticated. Get Information from UserDetailsService", username);
    OwncloudUserDetails owncloudUserDetails = userDetailsService.loadPreloadedUserByUsername(username, user);
    log.trace("Set the Password of User {} to the Authentication Object", username);
    owncloudUserDetails.setPassword(password);

    return new UsernamePasswordAuthenticationToken(owncloudUserDetails, password, grantedAuthoritiesMappingService.mapGrantedAuthorities(owncloudUserDetails));
  }

  @Override
  protected void checkFailure(String username, String uri, Ocs.Meta metaInformation) throws OwncloudStatusException {
    if ("ok".equals(metaInformation.getStatus())) {
      return;
    }
    log.warn("Authentication Failure with Authorization User {} and Code {} from Backend. Returned Failure-Message: {}",
        username, metaInformation.getStatuscode(), metaInformation.getMessage());
    throw new BadCredentialsException(messages.getMessage("AbstractUserDetailsAuthenticationProvider.badCredentials", "Bad Credentials"));
  }

  @Override
  public boolean supports(Class<?> authentication) {
    return OwncloudUtils.isAuthenticationClassSupported(authentication);
  }

}
