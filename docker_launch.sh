#!/bin/bash

mkdir -p data
cd data

if [[ -n $JMX_PORT ]]; then
	jmx_properties="-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=$JMX_PORT -Dcom.sun.management.jmxremote.rmi.port=$JMX_PORT -Dcom.sun.management.jmxremote.local.only=false -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false -Djava.rmi.server.hostname=0.0.0.0"
fi

java \
	-XX:+AlwaysPreTouch -XX:+UseNUMA \
	-XX:+ExplicitGCInvokesConcurrent -XX:+UseCompressedOops \
	-XX:+UseShenandoahGC -XX:+UnlockExperimentalVMOptions -XX:+ShenandoahPacing \
	-XX:MinHeapFreeRatio=15 -XX:MaxHeapFreeRatio=30 \
	$jmx_properties -jar ../seatofpants.jar
