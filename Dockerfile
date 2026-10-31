# Umsatz-Abruf als Container
#
# Bauen:   docker build -t umsatz-abruf .
# Starten: siehe docker-compose.yml (Anwendung + Caddy als Reverse-Proxy)
# Die Anwendung lauscht auf Port 8080 und speichert alles unter /data.

# --- Build ------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build

COPY pom.xml .
COPY src ./src
# Das Maven-Repository bleibt im BuildKit-Cache, damit nicht jeder Build alles neu lädt
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package

# --- Laufzeit ---------------------------------------------------------------
FROM eclipse-temurin:17-jre

# Ohne Root-Rechte laufen. UID/GID 1000 passt zum ersten Benutzer der meisten Linux-Hosts,
# damit die Dateien im gemounteten Datenverzeichnis dem Host-Benutzer gehören.
ARG UID=1000
ARG GID=1000
# Das Ubuntu-Basisimage bringt bereits einen Benutzer "ubuntu" mit UID 1000 mit.
RUN if getent passwd ${UID} >/dev/null; then userdel -r "$(getent passwd ${UID} | cut -d: -f1)" 2>/dev/null; fi; \
    if ! getent group ${GID} >/dev/null; then groupadd -g ${GID} umsatz; fi \
 && useradd -u ${UID} -g ${GID} -M -d /data -s /usr/sbin/nologin umsatz \
 && mkdir -p /data && chown ${UID}:${GID} /data

COPY --from=build /build/target/umsatz-abruf.jar /app/umsatz-abruf.jar

# Datenbank, Passport-Datei und Betriebszustand landen im Datenverzeichnis
# (relative Pfade aus der Konfiguration beziehen sich darauf)
WORKDIR /data
VOLUME /data

ENV TZ=Europe/Berlin \
    LANG=C.UTF-8

USER umsatz
EXPOSE 8080
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8", "-jar", "/app/umsatz-abruf.jar"]
