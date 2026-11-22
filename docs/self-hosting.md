# Run OpenService with Docker

OpenService 0.2.0-beta.1 is an Apache-2.0 self-hosted release for one team's service desk. Its core workflows run on your machine with Docker Engine and the Docker Compose plugin 2.24.4 or later. It uses your PostgreSQL volume, identities, DNS, TLS certificate and SMTP service. No OpenSoft account or cloud subscription is required.

## Install

Download `openservice-compose-0.2.0-beta.1.tar.gz`, the image archive for your Docker host (AMD64 or ARM64), and `SHA256SUMS` from the public release. Verify the downloaded files against their entries in the checksum list. Load the image archive with `docker load -i openservice-images-linux-amd64.tar.gz` (use `arm64` on ARM), then extract the Compose bundle. The bundle contains this guide, the Compose files, TLS proxy configuration and the license. Work from its directory. The published app image is `ghcr.io/opensoft-ge/openservice:0.2.0-beta.1`.

```sh
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:/setup" -w /setup ghcr.io/opensoft-ge/openservice:0.2.0-beta.1 setup
docker compose up -d --wait
```

Open `http://localhost:8090`. Sign in as `admin` with the password in the generated private `.env`. Running setup twice preserves it. Protect the directory and back up `SECRET_KEY` and credentials separately. Docker uses the versioned names already loaded from the archive.

## Open the desk

1. Open **Set up service desk**. A fresh database has a Service desk group and an editable **Request a work laptop** offering. Existing installations can install the starter explicitly on this page. Restarting preserves local edits, group membership and deactivated offerings.
2. Create or invite one agent and two requesters from Accounts. Add the agent to the service desk group. Change incident intake routing on the setup page. Edit catalog fulfillment routing on the offering.
3. Sign in as a requester. In **My requests**, choose **Report a problem** and submit the outage. The receipt shows the incident number, its current status and public replies. Another requester cannot open it. The ordinary request form remains available for service requests.
4. As the technician, open **Incidents**, find the incident assigned to your group, and select **Reply to employee or view reported problem**. Post a public reply and resolve the incident through its normal state transitions. Internal work notes stay in the staff record. Changing the incident visibility to private or group hides its employee receipt. The public view displays up to 100 recent replies.
5. Order the laptop as a requester. As a team member, claim and complete it in **Fulfillment**. The requester can see the outcome. The administrator can rename the offering, change its instructions and choose a different fulfillment group.

## Share over HTTPS and enable email

Point your DNS name to the Docker host. Put its certificate chain in `tls/fullchain.pem` and the corresponding private key in `tls/privkey.pem`. Restrict private key access. The HTTPS overlay exposes the TLS proxy on port 443 and removes the app's published HTTP port. PostgreSQL stays on the internal network. Replace these example values in the generated `.env` with your own. Configure any credentials as Compose literal values where necessary, including single quotes around passwords containing dollar signs.

```dotenv
PUBLIC_BASE_URL=https://desk.example.org
DELIVERY_ENABLED=true
MAIL_HOST=smtp.example.org
MAIL_PORT=587
MAIL_FROM=service-desk@example.org
MAIL_USERNAME=desk-mailer
MAIL_PASSWORD='your SMTP credential'
```

```sh
docker compose -f compose.yaml -f compose.https.yaml up -d --wait
```

The proxy sets the original host and HTTPS scheme. The app issues secure cookies, uses the configured public URL for links and requires STARTTLS with server certificate validation. For an internal SMTP CA, supply an operator-managed Java truststore in a read-only Compose volume and configure its path using `JAVA_TOOL_OPTIONS`. Keep validated TLS enabled. After replacing a web certificate, reload the proxy with `docker compose -f compose.yaml -f compose.https.yaml exec https nginx -s reload`.

Invite an authorized test recipient as a requester through **Account invitations**. Verify the recipient receives the email, accepts it, chooses a password, and can sign in. Use **Forgot password** to send a second message, reset the password and verify the old session loses access. Check **Delivery** for queued, delivered or failed attempts. A delivered status means that your relay accepted the message. Check the intended inbox too.

## Backup, restore and upgrade

Use the same Compose directory, project name and overlays each time. Before an upgrade, stop the application writer, dump the database and retain the **old** bundle, image and private configuration. The following runs against the shared deployment. Pick a new backup filename for each run.

```sh
umask 077
mkdir -p openservice-backups
docker compose -f compose.yaml -f compose.https.yaml stop app
docker compose exec -T db pg_dump -U openservice -d openservice -Fc > openservice-backups/before-upgrade.dump
docker compose exec -T db pg_restore --list < openservice-backups/before-upgrade.dump
```

Restart the existing service after a routine backup. For an upgrade, verify the dump first, then replace the versioned Compose files and images. Preserve the project name, volume, private `.env` and TLS directory. Run the shared `up -d --wait` command again. Schema changes run transactionally at startup. Check `/readyz`, sign in, inspect a known incident, and verify a restricted account and an attachment.

To rehearse recovery, create a separate Compose project with a new volume and a private copy of the required secrets. Bring up only its database (`docker compose -p desk-restore up -d --wait db`). Restore into that **empty** database, then start the app with outbound email and other external actions disabled. Use an unused loopback port. Preserve the original volume.

```sh
docker compose -p desk-restore exec -T db pg_restore --exit-on-error --single-transaction --no-owner -U openservice -d openservice < openservice-backups/before-upgrade.dump
docker compose -p desk-restore up -d --wait
```

If a release fails after migration, restore the pre-upgrade dump to a fresh volume with the previous release and its matching secrets. Review queued mail and external side effects before enabling workers. Schedule backups according to your data-loss tolerance and retain a protected copy off the Docker host.

## Optional Docker probe

The desk itself requires only the app and database, plus the proxy for shared HTTPS. The optional launch worker is the service availability probe. Configure a service objective, its on-call roster and probe location in Service Mapping. In the Compose directory, create `probe/location.json` and a private `probe.env` containing the authorized worker's `PROBE_AUTHORIZATION` value. The probe uses your installation's existing HTTP authorization policy. Keep the credentials out of the JSON file. Example configuration:

```json
{
  "operation": "probe_sample",
  "objective_id": "replace-with-objective-id",
  "location": "East",
  "location_health_url": "https://probe-health.example.org/ready",
  "endpoint": "https://checkout.example.org/health",
  "openservice_origin": "https://desk.example.org",
  "auth_env": "PROBE_AUTHORIZATION"
}
```

Start the worker with `docker compose -f compose.yaml -f compose.https.yaml -f compose.probe.yaml --profile probe up -d probe`. It samples every five minutes. Allow its state writes only for the intended installation and configure trusted CA files for private endpoints when needed. Inspect `docker compose -f compose.yaml -f compose.probe.yaml logs probe`. The separately documented endpoint collector and browser robot are outside this release's supported worker set.

## Release boundaries

The supported desk uses a single application instance and PostgreSQL 17. Teams operate DNS, SMTP, certificates, backups and upgrades. Live Entra/Jira recovery, Windows/Intune inventory, Confluence access revocation and high-availability failover have separate qualification requirements. Report functional issues in the OpenService GitHub repository. Report security issues privately to support@opensoft.ge.
