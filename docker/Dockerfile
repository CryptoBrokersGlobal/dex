FROM openjdk:8-jdk-stretch as builder
ARG SBT_VERSION=1.2.8
ARG WAVES_VERSION="latest"
ARG BRANCH="version-0.17.x"
ARG DEB_PACKAGE_NETWORKS

WORKDIR /home

ADD https://github.com/TurtleNetwork/dex/releases/download/v1.2.1/tn-dex-1.2.1.zip .
RUN unzip tn-dex-1.2.1.zip

FROM openjdk:8-jre-slim

#COPY entrypoint.sh /bin/
COPY --from=builder /home/tn-dex-1.2.1/ /usr/share/TN/
COPY entrypoint.sh /bin/

RUN groupadd -g 143 TN                                                   &&  \
    useradd -d /var/lib/TN -g 143 -u 143 -s /bin/bash -M TN           &&  \
    mkdir -p /etc/TN /var/lib/tn-dex/ /var/lib/tn-dex/data                                        &&  \
    chown -R 143:143 /var/lib/tn-dex/ /usr/share/TN /etc/TN             &&  \
    chmod -R 777 /var/lib/tn-dex/ /usr/share/TN /etc/TN                 &&  \
    ln -fs /log 


COPY main.conf /usr/share/TN/doc/

COPY application.ini /usr/share/TN/conf/

RUN apt-get -y update && apt-get -y install vim

EXPOSE 6886

USER TN
VOLUME /var/lib/TN
ENTRYPOINT ["/bin/entrypoint.sh"]