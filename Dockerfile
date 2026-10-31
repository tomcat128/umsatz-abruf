# Umsatz-Abruf als Container
#
# Bauen:   docker build -t umsatz-abruf .
# Starten: siehe docker-compose.yml (Anwendung + Caddy als Reverse-Proxy)
# Die Anwendung lauscht auf Port 8080 und speichert alles unter /data.

# --- Build ------------------------------------------------------------------
FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /build

COPY pom.xml .
COPY src ./src
# Das Maven-Repository bleibt im BuildKit-Cache, damit nicht jeder Build alles neu lädt
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package

# --- Laufzeit ---------------------------------------------------------------
FROM eclipse-temurin:25-jre

# Ohne Root-Rechte laufen. UID/GID 1000 passt zum ersten Benutzer der meisten Linux-Hosts,
# damit die Dateien im gemounteten Datenverzeichnis dem Host-Benutzer gehören.
ARG UID=1000
ARG GID=1000
# Das Ubuntu-Basisimage bringt bereits einen Benutzer "ubuntu" mit UID 1000 mit.
RUN if getent passwd ${UID} >/dev/null; then userdel -r "$(getent passwd ${UID} | cut -d: -f1)" 2>/dev/null; fi; \
    if ! getent group ${GID} >/dev/null; then groupadd -g ${GID} umsatz; fi \
 && useradd -u ${UID} -g ${GID} -M -d /data -s /usr/sbin/nologin umsatz \
 && mkdir -p /data /var/lib/umsatz-abruf \
 && chown ${UID}:${GID} /data /var/lib/umsatz-abruf \
 && chmod 700 /var/lib/umsatz-abruf

COPY --from=build /build/target/umsatz-abruf.jar /app/umsatz-abruf.jar

# Datenverzeichnis für die Datenbank (relative Pfade aus der Konfiguration beziehen sich darauf)
WORKDIR /data
VOLUME /data

# Internes Verzeichnis, das die Anwendung selbst verwaltet: Passport-Datei samt Schlüssel und
# Betriebszustand. Eigenes Volume, damit es Updates des Containers übersteht.
VOLUME /var/lib/umsatz-abruf

ENV TZ=Europe/Berlin \
    LANG=C.UTF-8 \
    INTERN_VERZEICHNIS=/var/lib/umsatz-abruf

USER umsatz
EXPOSE 8080
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8", "-jar", "/app/umsatz-abruf.jar"]
