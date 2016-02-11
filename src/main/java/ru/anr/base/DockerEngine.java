/*
 * Copyright 2015 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package ru.anr.base;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.BuildResponseItem;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.BuildImageResultCallback;

/**
 * The Engine is a wrapper around Docker interfaces for mostly used cases.
 *
 *
 * @author Alexey Romanchuk
 * @created May 19, 2015
 *
 */
public class DockerEngine extends BaseParent {

    /**
     * Logger
     */
    private static final Logger logger = LoggerFactory.getLogger(DockerEngine.class);

    /**
     * Docker client
     */
    private final DockerClient docker;

    /**
     * This constructor creates a client instance
     */
    public DockerEngine() {

        super();

        /*
         * For MacOS X and Linux the way to communicate may differ
         */
        String value = System.getProperty("docker.io.url", "unix:///var/run/docker.sock");
        logger.info("Using DOCKER URL: {}", value);

        docker = DockerClientBuilder.getInstance(value).build();
    }

    /**
     * Stops the container specified by identifier ignoring any errors
     * 
     * @param containerId
     *            The container id
     */
    public void stop(String containerId) {

        runIgnored(x -> {
            docker.stopContainerCmd(containerId).exec();
            docker.waitContainerCmd(containerId).exec();
        });
    }

    /**
     * Removes the container specified by identifier ignoring any errors
     * 
     * @param containerId
     *            The container id
     */
    public void remove(String containerId) {

        runIgnored(x -> {
            docker.removeContainerCmd(containerId).exec();
        });
    }

    /**
     * Builds an image by specified parameters
     * 
     * @param directory
     *            A directory with "Dockerfile"
     * @param tagToUse
     *            A local tag to use for the image
     */
    public void build(File directory, String tagToUse) {

        BuildImageResultCallback callback = new BuildImageResultCallback() {

            /**
             * {@inheritDoc}
             */
            @Override
            public void onNext(BuildResponseItem item) {

                logger.debug(item.getStream());
                super.onNext(item);
            }
        };
        docker.buildImageCmd(directory).withTag(tagToUse).exec(callback).awaitImageId();
    }

    /**
     * Executes the command line specified in arguments in a container
     * 
     * (like 'cat /tmp/txt.txt')
     * 
     * @param containerId
     *            The identifier of a container
     * @param cmds
     *            List of commands to execute inside of the container
     * @return The command output
     */
    public String exec(String containerId, String... cmds) {

        ExecCreateCmdResponse rs = docker.execCreateCmd(containerId).withAttachStdout(true).withCmd(cmds).exec();

        /*
         * The s.trim() operation is required to remove some bad symbols from
         * the output
         */
        return read(docker.execStartCmd(containerId).withExecId(rs.getId()).exec()).stream().map(s -> s.trim())
                .collect(Collectors.joining("\n"));
    }

    /**
     * Creates and runs a container by the given image
     * 
     * @param image
     *            The image to use
     * @param name
     *            The name to use for the new container
     * @param cmd
     *            The command to use
     * @param envs
     *            The en
     * @param portPairs
     *            The pairs with bindings (host,container,host,container)
     * @return The identifier of the container
     */
    public String start(String image, String name, String cmd, String[] envs, Integer... portPairs) {

        Map<Integer, Integer> map = toMap(portPairs);
        final Ports bindings = new Ports();

        map.forEach((h, c) -> {
            bindings.bind(ExposedPort.tcp(c), Ports.Binding(h));
        });

        return start(image, name, c -> {
            c.withCmd(StringUtils.split(cmd, " ")).withPortBindings(bindings).withEnv(envs);
        });
    }

    /**
     * Creates and runs a container with the manual way to configure its
     * parameters
     * 
     * @param image
     *            The image to use
     * @param name
     *            The name to use for the new container
     * @param callback
     *            A special callback to get {@link CreateContainerCmd} for
     *            further configuration.
     * @return The identifier of new created container
     */
    public String start(String image, String name, DockerRunConfigurator callback) {

        CreateContainerCmd cmd = docker.createContainerCmd(image).withName(name);
        callback.configure(cmd);

        CreateContainerResponse rs = cmd.exec();

        String id = rs.getId();
        docker.startContainerCmd(id).exec();

        return id;
    }

    /**
     * Returns all active containers
     * 
     * @return A map which has the identifiers of containers as the keys
     */
    public Map<String, Container> getActive() {

        return toMap(client().listContainersCmd().withShowAll(true).exec(), Container::getId, c -> c);
    }

    // //////////////////////////// helpers ///////////////////////////////////

    /**
     * Reads data as a list of strings
     * 
     * @param response
     *            A response
     * @return The list of strings
     */
    private List<String> read(InputStream response) {

        try {
            return IOUtils.readLines(response, "UTF-8");
        } catch (IOException ex) {
            throw new ApplicationException(ex);
        } finally {
            IOUtils.closeQuietly(response);
        }
    }

    // /////////////////////////////////////////////////////////////////////////
    // /// getters/setters
    // /////////////////////////////////////////////////////////////////////////

    /**
     * @return the client
     */
    public DockerClient client() {

        return docker;
    }
}
