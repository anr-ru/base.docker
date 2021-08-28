package ru.anr.base;

import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import com.github.dockerjava.api.model.Identifier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ContextConfiguration;
import ru.anr.base.tests.BaseTestCase;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * Tests for the Engine
 *
 * @author Alexey Romanchuk
 * @created Feb 10, 2016
 */
@ContextConfiguration(classes = DockerEngineTest.class)
public class DockerEngineTest extends BaseTestCase {

    /**
     * Testing the Image LifeCycle
     *
     * @throws IOException If Dockerfile is not found
     */
    @Test
    public void testBasic() throws IOException {

        DockerEngine e = new DockerEngine();

        File f = new ClassPathResource("Dockerfile").getFile();
        e.build(f, "xxx", "latest");

        String name = guid();
        String id = e.start("xxx:latest", name, "sleep 10s", new String[]{"CC_NAME=Me"}, 17474, 7474, 17575, 7575);

        Map<String, Container> map = e.getActive();

        Assertions.assertFalse(map.isEmpty());
        Assertions.assertTrue(map.containsKey(id));

        Container c = map.get(id);
        Assertions.assertEquals("xxx:latest", c.getImage());
        assertContains(c.getNames()[0], name);

        String rs = e.exec(id, "env");
        log(rs);

        assertContains(rs, "CC_NAME=Me");

        Assertions.assertEquals(4, c.getPorts().length);
        ContainerPort[] ports = c.getPorts();

        ContainerPort p = first(filter(list(ports), i -> Integer.valueOf(7575).equals(i.getPrivatePort())));
        Assertions.assertNotNull(p);

        Assertions.assertEquals(17575, Objects.requireNonNull(p.getPublicPort()).intValue());
        Assertions.assertEquals("tcp", p.getType());

        p = first(filter(list(ports), i -> Integer.valueOf(7474).equals(i.getPrivatePort())));
        Assertions.assertEquals(17474, Objects.requireNonNull(p.getPublicPort()).intValue());
        Assertions.assertEquals("tcp", p.getType());

        String newImage = e.commit(id, "fedora", "21x");

        e.stop(id);
        e.remove(id);

        // Running a container based on the recent image
        id = e.start("fedora:21x", guid(), x -> x.withCmd("sleep", "15s"));

        // Checking the variable exists
        rs = e.exec(id, "env");
        assertContains(rs, "CC_NAME=Me");

        e.stop(id);
        e.remove(id);

        e.removeImage(newImage);
    }

    /**
     * Checking how the parse works
     */
    @Test
    public void parsing() {

        Identifier i = Identifier.fromCompoundString("registry.my.com/somerepo:1.0.0.2x");
        Assertions.assertEquals("registry.my.com/somerepo", i.repository.name);
        Assertions.assertEquals("somerepo", i.repository.getPath());
        Assertions.assertEquals("1.0.0.2x", i.tag.orElse(""));
    }
}
