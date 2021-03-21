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

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.UnsupportedEncodingException;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The Engine is a wrapper around Docker interfaces for mostly used cases.
 *
 * @author Alexey Romanchuk
 * @created May 19, 2015
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

        this(null);
    }

    /**
     * This constructor creates a client instance in case if the docker host
     * differs from the default.
     *
     * @param dockerUrl The Docker Url (e.g.tcp://docker:2375)
     */
    public DockerEngine(String dockerUrl) {

        super();
        docker = (isEmpty(dockerUrl) ?
                DockerClientBuilder.getInstance() :
                DockerClientBuilder.getInstance(DefaultDockerClientConfig.createDefaultConfigBuilder()
                        .withDockerHost(dockerUrl)
                        .build()))
                .build();
    }

    /**
     * Stops the container specified by identifier ignoring any errors
     *
     * @param containerId The container id
     */
    public void stop(String containerId) {

        runIgnored(x -> {
            docker.stopContainerCmd(containerId).exec();
            docker.waitContainerCmd(containerId).exec(new WaitContainerResultCallback()).awaitCompletion();
        });
    }

    /**
     * Removes the container specified by identifier ignoring any errors
     *
     * @param containerId The container id
     */
    public void remove(String containerId) {
        runIgnored(x -> docker.removeContainerCmd(containerId).exec());
    }

    /**
     * Removes (forcedly) the specified image
     *
     * @param imageId The identifier of the image
     */
    public void removeImage(String imageId) {

        logger.info("Removing the image: {}", imageId);
        runIgnored(x -> docker.removeImageCmd(imageId).withForce(true).exec());
    }

    /**
     * Builds an image by specified parameters
     *
     * @param directory  A directory with "Dockerfile"
     * @param repository A repository to use
     * @param tag        A tag to use
     */
    public void build(File directory, String repository, String tag) {

        BuildImageResultCallback callback = new BuildImageResultCallback() {

            @Override
            public void onNext(BuildResponseItem item) {
                logger.debug("Build: {}", item.getStream());
                super.onNext(item);
            }
        };
        String imageId = docker.buildImageCmd(directory).exec(callback).awaitImageId();
        docker.tagImageCmd(imageId, repository, tag).exec();
    }

    /**
     * Pulls the given image from the specified repository
     *
     * @param repository The repository (the full name)
     * @param cfg        The authenticate configuration
     */
    public void pull(String repository, AuthConfig cfg) {

        Identifier identifier = Identifier.fromCompoundString(repository);

        PullImageCmd pull = docker.pullImageCmd(identifier.repository.name);
        identifier.tag.ifPresent(pull::withTag);
        if (cfg != null) {
            pull.withAuthConfig(cfg);
        }
        try {
            pull.exec(new PullImageResultCallback()).awaitCompletion();
        } catch (InterruptedException ex) {
            throw new ApplicationException(ex);
        }
    }

    /**
     * Pushing the image given by separate parts
     *
     * @param repository The name of a repository
     * @param tag        A tag
     * @param cfg        The configuration for accessing to repositories
     */
    public void push(String repository, String tag, AuthConfig cfg) {

        Identifier identifier = Identifier.fromCompoundString(repository + ":" + tag);

        PushImageCmd push = docker.pushImageCmd(identifier);
        if (cfg != null) {
            push.withAuthConfig(cfg);
        }
        try {
            push.exec(new ResultCallback.Adapter<>()).awaitCompletion();
        } catch (InterruptedException ex) {
            throw new ApplicationException(ex);
        }
    }

    /**
     * Executes the command line specified in arguments in a container
     * <p>
     * (like 'cat /tmp/txt.txt')
     *
     * @param containerId The identifier of a container
     * @param cmds        List of commands to execute inside of the container
     * @return The command output
     */
    public String exec(String containerId, String... cmds) {

        ExecCreateCmdResponse rs = docker.execCreateCmd(containerId).withAttachStdout(true).withAttachStderr(true)
                .withTty(true).withCmd(cmds).exec();

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {

            docker.execStartCmd(containerId)
                    .withExecId(rs.getId())
                    .exec(new ExecStartResultCallback(out, out))
                    .awaitCompletion();

            String s = out.toString(DEFAULT_CHARSET.name());
            return s == null ? "" : s.replaceAll("[^\\x20-\\x7E\n\r]", "");

        } catch (InterruptedException | UnsupportedEncodingException ex) {
            throw new ApplicationException(ex);
        }
    }

    /**
     * Creates and runs a container by the given image
     *
     * @param image     The image to use
     * @param name      The name to use for the new container
     * @param cmd       The command to use
     * @param envs      The en
     * @param portPairs The pairs with bindings (host,container,host,container)
     * @return The identifier of the container
     */
    public String start(String image, String name, String cmd, String[] envs, Integer... portPairs) {

        final Ports bindings = getBindings(portPairs);
        return start(image, name, c -> {
            Objects.requireNonNull(c.withCmd(StringUtils.split(cmd, " ")).
                    getHostConfig())
                    .withPortBindings(bindings);
            if (envs != null) {
                c.withEnv(envs);
            }
        });
    }

    /**
     * Converts a plain port binding to the special structure suitable for
     * processing by the engine.
     *
     * @param portPairs The pairs (host,container) for the required ports
     * @return A {@link Ports} object instance
     */
    public static Ports getBindings(Integer... portPairs) {

        Map<Integer, Integer> map = toMap(portPairs);
        final Ports bindings = new Ports();

        map.forEach((h, c) -> bindings.bind(ExposedPort.tcp(c), Ports.Binding.bindPort(h)));
        return bindings;
    }

    /**
     * Creates and runs a container with the manual way to configure its
     * parameters
     *
     * @param image    The image to use
     * @param name     The name to use for the new container
     * @param callback A special callback to get {@link CreateContainerCmd} for
     *                 further configuration.
     * @return The identifier of new created container
     */
    public String start(String image, String name, Consumer<CreateContainerCmd> callback) {

        CreateContainerCmd cmd = docker.createContainerCmd(image).withName(name);
        callback.accept(cmd);

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

        return toMap(client().listContainersCmd().withShowAll(false).exec(), Container::getId, c -> c);
    }

    /**
     * Committing the container to fix and store all changes as a new image
     *
     * @param containerId The identifier of the container
     * @param repository  The name of a repository to use
     * @param tag         The tag to use
     * @return The identifier of the newly created image
     */
    public String commit(String containerId, String repository, String tag) {

        return docker.commitCmd(containerId).withRepository(repository).withTag(tag).exec();
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
