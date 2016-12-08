package software.coolstuff.springframework.owncloud.service.impl.resource.file;

import org.springframework.core.io.Resource;
import org.springframework.test.context.ActiveProfiles;

import software.coolstuff.springframework.owncloud.config.CompareResourceAfter;
import software.coolstuff.springframework.owncloud.config.OwncloudResourceFileTest;
import software.coolstuff.springframework.owncloud.service.AbstractOwncloudUserModificationServiceTest;

@ActiveProfiles("RESOURCE-FILE-TEST")
public class OwncloudUserModificationServiceResourceClasspathTest extends AbstractOwncloudUserModificationServiceTest
    implements OwncloudResourceFileTest {

  @CompareResourceAfter("testSaveUser_CreateUser_OK_WithoutGroups")
  public void compareAfterTestSaveUser_CreateUser_OK_WithoutGroups(Resource target) throws Exception {
    compareResources(getResourceOf("owncloud_afterSave_User3"), target);
  }

  @CompareResourceAfter("testSaveUser_CreateUser_OK_WithGroups")
  public void compareAfterTestSaveUser_CreateUser_OK_WithGroups(Resource target) throws Exception {
    compareResources(getResourceOf("owncloud_afterSave_User4"), target);
  }
}
