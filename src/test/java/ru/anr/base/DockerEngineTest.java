/**
 * 
 */
package ru.anr.base;

import java.io.IOException;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ContextConfiguration;

import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Container.Port;

import ru.anr.base.tests.BaseTestCase;

/**
 * Tests for the Engine
 *
 *
 * @author Alexey Romanchuk
 * @created Feb 10, 2016
 *
 */
@ContextConfiguration(classes = DockerEngineTest.class)
public class DockerEngineTest extends BaseTestCase {

    /**
     * Testing the Image LifeCycle
     * 
     * @throws IOException
     *             If Dockerfile is not found
     */
    @Test
    public void testBasic() throws IOException {

        DockerEngine e = new DockerEngine();

        e.build(new ClassPathResource("Dockerfile").getFile(), "xxx:latest");

        String name = guid();
        String id = e.start("xxx:latest", name, "sleep 10s", new String[]{ "CC_NAME=Me" }, 17474, 7474, 17575, 7575);

        Map<String, Container> map = e.getActive();

        Assert.assertTrue(map.size() >= 1);
        Assert.assertTrue(map.containsKey(id));

        Container c = map.get(id);
        Assert.assertEquals("xxx:latest", c.getImage());
        assertContains(c.getNames()[0], name);

        String rs = e.exec(id, "env");
        log(rs);

        assertContains(rs, "CC_NAME=Me");

        Assert.assertEquals(2, c.getPorts().length);
        Port[] ports = c.getPorts();

        Port p = first(filter(list(ports), i -> i.getPrivatePort().intValue() == 7575));
        Assert.assertNotNull(p);

        Assert.assertEquals(17575, p.getPublicPort().intValue());
        Assert.assertEquals("tcp", p.getType());

        p = first(filter(list(ports), i -> i.getPrivatePort().intValue() == 7474));
        Assert.assertEquals(17474, p.getPublicPort().intValue());
        Assert.assertEquals("tcp", p.getType());

        String newImage = e.commit(id, "fedora", "21x");

        e.stop(id);
        e.remove(id);

        // Running a container based on the recent image
        id = e.start("fedora:21x", guid(), x -> {
            x.withCmd("sleep", "15s");
        });

        // Checking the variable exists
        rs = e.exec(id, "env");
        assertContains(rs, "CC_NAME=Me");

        e.stop(id);
        e.remove(id);

        e.client().removeImageCmd(newImage);
    }
}
