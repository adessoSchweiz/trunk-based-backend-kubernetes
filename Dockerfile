FROM jboss/keycloak-adapter-wildfly:3.4.3.Final

MAINTAINER Robert Brem <brem_robert@hotmail.com>

ADD target/*.war /opt/jboss/wildfly/standalone/deployments/