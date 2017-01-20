/* Copyright */
package thredds.tds;

import org.junit.Assert;
import org.junit.Test;
import thredds.TestWithLocalServer;
import ucar.httpservices.HTTPFactory;
import ucar.httpservices.HTTPMethod;
import ucar.httpservices.HTTPSession;

/**
 * Test that restricted datasets fail when not authorized
 *
 * @author caron
 * @since 4/21/2015
 */
public class TestRestrictNoAuth {

  @Test
  public void testFailNoAuth() {
    String endpoint = TestWithLocalServer.withPath("/dodsC/testRestrictedDataset/testData2.nc.dds");
    System.out.printf("testRestriction req = '%s'%n", endpoint);

    try (HTTPSession session = HTTPFactory.newSession(endpoint)) {
      HTTPMethod method = HTTPFactory.Get(session);
      int statusCode = method.execute();

      Assert.assertTrue(statusCode== 401 || statusCode == 403);

    } catch (ucar.httpservices.HTTPException e) {

      System.out.printf("Should return 401|403 err=%s%n", e.getMessage());
      assert false;

    } catch (Exception e) {

      e.printStackTrace();
      assert false;
    }
  }
}
