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
package software.coolstuff.springframework.owncloud.service.impl.local.file;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.context.ActiveProfiles;
import software.coolstuff.springframework.owncloud.config.CompareResourceAfter;
import software.coolstuff.springframework.owncloud.service.impl.local.AbstractLocalOwncloudUserServiceTest;

@ActiveProfiles("LOCAL-FILE-USER-SERVICE")
public class OwncloudLocalUserServiceFileTest extends AbstractLocalOwncloudUserServiceTest implements OwncloudLocalModifyingFileTest {

  @Autowired
  private ResourceLoader resourceLoader;

  @Override
  public String getResourcePrefix() {
    return "/modificationService";
  }

  @CompareResourceAfter("testSaveUser_CreateUser_OK_WithoutGroups")
  public void compareAfterTestSaveUser_CreateUser_OK_WithoutGroups(Resource target) throws Exception {
    compareResources(getResourceOf(resourceLoader, "owncloud_afterSave_User3"), target);
  }

  @CompareResourceAfter("testSaveUser_CreateUser_OK_WithGroups")
  public void compareAfterTestSaveUser_CreateUser_OK_WithGroups(Resource target) throws Exception {
    compareResources(getResourceOf(resourceLoader, "owncloud_afterSave_User4"), target);
  }

  @CompareResourceAfter("testSaveUser_UpdateUser_OK_WithoutGroups")
  public void compareAfterTestSaveUser_UpdateUser_OK_WithoutGroups(Resource target) throws Exception {
    compareResources(getResourceOf(resourceLoader, "owncloud_afterSave_User2"), target);
  }

  @CompareResourceAfter("testSaveUser_UpdateUser_OK_WithGroups")
  public void compareTestSaveUser_UpdateUser_OK_WithGroups(Resource target) throws Exception {
    compareResources(getResourceOf(resourceLoader, "owncloud_afterSave_User1"), target);
  }

  @CompareResourceAfter("testDeleteUser_OK")
  public void compareTestDeleteUser_OK(Resource target) throws Exception {
    compareResources(getResourceOf(resourceLoader, "owncloud_afterDelete_User1"), target);
  }

  @CompareResourceAfter("testCreateGroup_OK")
  public void compareTestCreateGroup_OK(Resource target) throws Exception {
    compareResources(getResourceOf(resourceLoader, "owncloud_afterCreate_Group4"), target);
  }

  @CompareResourceAfter("testDeleteGroup_OK")
  public void compareTestDeleteGroup_OK(Resource target) throws Exception {
    compareResources(getResourceOf(resourceLoader, "owncloud_afterDelete_Group1"), target);
  }
}
