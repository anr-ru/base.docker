/**
 * 
 */
package ru.anr.base;

import com.github.dockerjava.api.command.CreateContainerCmd;

/**
 * The Docker callback for configurations of container.
 *
 *
 * @author Alexey Romanchuk
 * @created Feb 11, 2016
 *
 */

@FunctionalInterface
public interface DockerRunConfigurator {

    /**
     * Configure the creation command
     * 
     * @param cmd
     *            The creation command
     */
    void configure(CreateContainerCmd cmd);
}
