#!/usr/bin/env bash
# Requires the serialized build lease. Does not build an image, start Keycloak or modify a realm.
set -euo pipefail
export LC_ALL=C

module_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "$module_dir/../.." && pwd)"
output="${1:-$module_dir/build/package}"
if [[ "$#" -gt 1 || -e "$output" ]]; then
  printf '%s\n' 'Use one new, absent package output directory.' >&2
  exit 1
fi
case "$output" in /*) ;; *) output="$PWD/$output" ;; esac

# Wrapper checksum and standalone dependency pin are part of the packaging contract.
grep -Fqx 'distributionUrl=https\://services.gradle.org/distributions/gradle-8.14.3-bin.zip' "$repo_dir/gradle/wrapper/gradle-wrapper.properties"
grep -Fqx 'distributionSha256Sum=bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531' "$repo_dir/gradle/wrapper/gradle-wrapper.properties"
grep -Fqx "def keycloakVersion = '26.7.4'" "$module_dir/build.gradle"
javac -version 2>&1 | grep -E '^javac 21\.' >/dev/null

# Do not alter the leased build file. Apply deterministic archive settings through a generated init script.
mkdir -p "$module_dir/build"
init_script="$(mktemp "$module_dir/build/package-init.XXXXXX.gradle")"
trap 'rm -f -- "$init_script"' EXIT
cat > "$init_script" <<'GRADLE'
allprojects {
    tasks.withType(org.gradle.api.tasks.bundling.AbstractArchiveTask).configureEach {
        preserveFileTimestamps = false
        reproducibleFileOrder = true
    }
    tasks.withType(JavaCompile).configureEach { options.encoding = 'UTF-8' }
    tasks.register('packageDependencyProvenance') {
        doLast {
            def lines = new TreeSet<String>()
            ['compileClasspath', 'testRuntimeClasspath'].each { name ->
                configurations.getByName(name).resolvedConfiguration.resolvedArtifacts.each { artifact ->
                    def digest = java.security.MessageDigest.getInstance('SHA-256')
                    artifact.file.withInputStream { stream ->
                        byte[] buffer = new byte[16384]
                        int count
                        while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count)
                    }
                    lines.add("${digest.digest().encodeHex()}  ${artifact.moduleVersion.id}:${artifact.classifier ?: ''}@${artifact.extension}")
                }
            }
            layout.buildDirectory.file('package-dependencies.sha256').get().asFile.setText(lines.join('\n') + '\n', 'UTF-8')
        }
    }
}
GRADLE
bash "$repo_dir/gradlew" --project-dir "$module_dir" --no-daemon --max-workers=1 --init-script "$init_script" check jar packageDependencyProvenance

artifact="$module_dir/build/libs/armoney-keycloak-provider-0.1.0.jar"
test -s "$artifact"

# Fail packaging if a future source edit registers a proof/default-protocol factory.
services="$module_dir/src/main/resources/META-INF/services"
test "$(find "$services" -type f | wc -l | tr -d ' ')" = 3
printf '%s\n' com.arman.bank.sso.EmailVerificationFactory com.arman.bank.sso.PhoneVerificationFactory | cmp - "$services/org.keycloak.authentication.RequiredActionFactory"
printf '%s\n' com.arman.bank.sso.PasswordProofFactory | cmp - "$services/org.keycloak.authentication.AuthenticatorFactory"
printf '%s\n' com.arman.bank.sso.StatusResourceFactory | cmp - "$services/org.keycloak.services.resource.RealmResourceProviderFactory"

audit_dir="$(mktemp -d "$module_dir/build/package-audit.XXXXXX")"
(cd -- "$audit_dir" && jar --extract --file "$artifact" META-INF/services)
test "$(find "$audit_dir/META-INF/services" -type f | wc -l | tr -d ' ')" = 3
for descriptor in "$services"/*; do
  cmp "$descriptor" "$audit_dir/META-INF/services/${descriptor##*/}"
done

mkdir -p -- "$(dirname -- "$output")"
mkdir -- "$output"
mkdir -p "$output/providers" "$output/themes/armoney/login" "$output/provenance"
cp "$artifact" "$output/providers/armoney-keycloak-provider.jar"
cp "$module_dir/build/package-dependencies.sha256" "$output/provenance/dependency-files.sha256"
cp -R "$repo_dir/sso-service/themes/armoney/login/." "$output/themes/armoney/login/"

# Hash precisely the allowlisted build inputs; no .git, credentials, realm exports or sibling service files.
(
  cd -- "$repo_dir"
  find gradlew gradle/wrapper sso-service/Dockerfile \
    sso-service/provider/build.gradle sso-service/provider/settings.gradle \
    sso-service/provider/package-provider.sh sso-service/provider/src \
    sso-service/themes/armoney/login -type f -print0 |
    sort -z | xargs -0 sha256sum
) > "$output/provenance/source-files.sha256"
(
  cd -- "$output"
  find providers themes -type f -print0 | sort -z | xargs -0 sha256sum
) > "$output/provenance/package-files.sha256"
(
  cd -- "$output/provenance"
  printf '%s\n' \
    'format=armoney-keycloak-package-v1' \
    'coordinate=com.arman.bank:armoney-keycloak-provider:0.1.0' \
    'keycloak=26.7.4' \
    'gradle=8.14.3' \
    'gradle_distribution_sha256=bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531' \
    'builder=gradle:8.14.3-jdk21@sha256:21bd311ed01360c189b8870c6b6e988199ff10f72d445d02fb39d3cff9da91d7' \
    'base=quay.io/keycloak/keycloak:26.7.4@sha256:82a77884f3af238beab1e7afd63b5f530e1b5c0590bd7aa60b40a40463e29b2c' \
    'proof_factories_registered=false' \
    'realm_configuration_applied=false' \
    'source_revision=unrecorded; source-files.sha256 identifies actual input bytes' \
    'validation=standalone-provider check jar packageDependencyProvenance; not runtime acceptance'
  sha256sum source-files.sha256 package-files.sha256 dependency-files.sha256
) > "$output/provenance/manifest.txt"
printf '%s\n' "Provider package created: $output" 'Inspect provenance/manifest.txt and retain test reports before integration.'
