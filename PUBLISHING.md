# Publishing to Maven Central

The bindings are published as `com.edifabric:edifabric-x12-native`: one jar that bundles the
ediFabric Native library for every supported platform, plus the sources and javadoc jars and
their signatures that Maven Central requires.

| Classpath resource | Platform |
| --- | --- |
| `com/edifabric/nativex12/native/win-x64/edifabric-x12-tools.dll` | Windows x64 |
| `com/edifabric/nativex12/native/linux-x64/edifabric-x12-tools.so` | Linux x64, glibc 2.34+ |
| `com/edifabric/nativex12/native/linux-arm64/edifabric-x12-tools.so` | Linux arm64, glibc 2.34+ |
| `com/edifabric/nativex12/native/osx-arm64/edifabric-x12-tools.dylib` | macOS 12+ on Apple silicon |

When no other library is found, `EdiFabricX12.loadLibrary` copies the matching resource to
`java.io.tmpdir/edifabric-x12/<hash>/` and loads it from there. The libraries come from the
public downloads at `https://www.edifabric.com/download/`, so publish a release only after the
native build for that version is uploaded there.

| File | Purpose |
| --- | --- |
| `pom.xml` | version, Central metadata, and the `release` profile (natives check, sources, javadoc, gpg, upload) |
| `mvnw`, `mvnw.cmd`, `.mvn/` | Maven Wrapper pinned to Maven 3.9.11; Maven 3.10 adds `maven-metadata-local.xml` to the bundle, which Central rejects |
| `scripts/fetch-natives.ps1`, `.sh` | download the four libraries into `natives/<rid>/` |
| `scripts/SmokeTest.java` | check that the jar loads its bundled library |
| `scripts/release.ps1`, `.sh` | fetch, build, smoke-test, and deploy from your machine |
| `.github/workflows/publish-maven.yml` | the same pipeline in GitHub Actions, with a smoke test on every platform |

## One-time setup

1. Sign in to the [Central Portal](https://central.sonatype.com) and add the namespace
   `com.edifabric` (**Namespaces > Add Namespace**). The portal shows a verification key; add it
   as a DNS TXT record on `edifabric.com`, then click **Verify**.
2. Create a signing key and publish its public half, because Central rejects unsigned or
   unverifiable artifacts:

   ```bash
   gpg --quick-generate-key "EdiFabric <support@edifabric.com>" rsa4096 sign 3y
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   gpg --armor --export-secret-keys <KEY_ID> > private.asc   # for the GitHub secret; delete afterwards
   ```

3. In the Central Portal, open **View Account > Generate User Token**. Use that username and
   password (not your login) for publishing.
4. GitHub: create the environment `maven-central` (**Settings > Environments**), optionally with
   required reviewers, and add these secrets to it:

   | Secret | Value |
   | --- | --- |
   | `MAVEN_CENTRAL_USERNAME` | user token username |
   | `MAVEN_CENTRAL_PASSWORD` | user token password |
   | `GPG_PRIVATE_KEY` | contents of `private.asc` |
   | `GPG_PASSPHRASE` | the key's passphrase |

5. For publishing from your machine only: install JDK 11+ and gpg with the signing key (the
   scripts run Maven through `./mvnw`, which downloads Maven 3.9.11), and add the user token to
   `~/.m2/settings.xml`:

   ```xml
   <settings>
     <servers>
       <server>
         <id>central</id>
         <username>USER_TOKEN_USERNAME</username>
         <password>USER_TOKEN_PASSWORD</password>
       </server>
     </servers>
   </settings>
   ```

## Release

1. Upload the new native build to `https://www.edifabric.com/download/`.
2. Set `<version>` in `pom.xml` to the native library version, for example `1.1.1`, update the
   version in `README.md`, and commit.
3. Validate the jar:
   - GitHub: **Actions > Publish to Maven Central > Run workflow** with **publish** cleared
   - or locally: `./scripts/release.ps1 -DryRun` (Windows) or `scripts/release.sh --dry-run`
4. Optionally check what Central receives: run the workflow with **publish** checked and
   **auto-publish** cleared, or `./scripts/release.ps1`. The deployment waits under
   **Deployments** in the Central Portal, which shows its validation result. Click **Drop**
   when done, then continue with step 5. (Clicking **Publish** instead releases it, and step 5
   would then fail because the version already exists.)
5. Create a GitHub release with the tag `v<version>`, for example `v1.1.1`. Publishing the
   release runs the workflow and publishes automatically; the workflow fails if the tag and the
   version differ. Locally, the equivalent is `./scripts/release.ps1 -AutoPublish` or
   `scripts/release.sh --auto-publish`.

Once published, a version can never be changed or removed; it shows up for Maven and Gradle
within about 30 minutes, and in search on central.sonatype.com later. If something is wrong,
bump the version and release again.
