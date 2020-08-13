#!/bin/bash

if [ ! -f /etc/TN/main.conf ]; then
	echo "Custom '/etc/TN/main.conf' not found. Using a default one for network."
	cp /usr/share/TN/doc/main.conf /etc/TN/main.conf
else
	echo "Found custom '/etc/TN/TN.conf'. Using it."
fi

if [ ! -f /etc/TN/logback.xml ]; then
	echo "Custom '/etc/TN/logback.xml' not found. Using a default one for network."
	cp /usr/share/TN/doc/logback.xml /etc/TN/logback.xml
else
	echo "Found custom '/etc/TN/logback.xml'. Using it."
fi

#/usr/share/TN/bin/waves-dex-cli create-account-storage --address-scheme l --seed-format base64 --account-nonce 3 --output-directory /var/lib/tn-dex
cat /usr/share/TN/conf/application.ini

/usr/share/TN/bin/tn-dex -Dlogback.configurationFile=/etc/TN/logback.xml /etc/TN/main.conf