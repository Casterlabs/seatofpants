FROM eclipse-temurin:21-jre-ubi9-minimal
WORKDIR /home/container

LABEL org.opencontainers.image.source="https://github.com/casterlabs/seatofpants"

# install docker (so that SOP's Docker provider can work)
RUN microdnf -y install yum-utils
RUN yum-config-manager --add-repo https://download.docker.com/linux/rhel/docker-ce.repo
RUN microdnf -y install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# code
COPY ./target/seatofpants.jar /home/container
COPY ./docker_launch.sh /home/container
RUN chmod +x docker_launch.sh

# entrypoint
CMD [ "./docker_launch.sh" ]
EXPOSE 10246/tcp
