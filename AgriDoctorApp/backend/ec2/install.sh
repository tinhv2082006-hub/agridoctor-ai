#!/usr/bin/env bash
set -euo pipefail

source_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
root=/opt/agridoctor
config=/etc/agridoctor/backend.env

sudo -n install -d -m 0755 "$root/backend"
sudo -n install -d -m 0700 /etc/agridoctor
sudo -n python3.12 -m venv "$root/venv"
sudo -n "$root/venv/bin/python" -m pip --isolated install --no-cache-dir -r "$source_dir/requirements-ec2.txt"
# The SSH staging shell can use umask 077. Let the service users read the runtime.
sudo -n chmod -R a+rX "$root/venv"
sudo -n bash -c 'umask 077; if [[ ! -e "$1" ]]; then
    token=$(python3.12 -c "import secrets; print(secrets.token_urlsafe(32))")
    printf "AGRIDOCTOR_AI_TOKEN=%s\n" "$token" > "$1"
fi' _ "$config"

files=(admin_access.py admin.html agent_workflow.py agridoctor.sqlite chat_agent.py
       conversation.py ec2_api.py image_validation.py local_model.py lookup.py
       server.py transcription.py vision.py)
for file in "${files[@]}"; do
    sudo -n install -m 0644 "$source_dir/$file" "$root/backend/$file"
done
sudo -n install -m 0644 "$source_dir"/ec2/agridoctor-{core,api}.service /etc/systemd/system/
sudo -n systemctl daemon-reload
sudo -n systemctl enable agridoctor-api.service
sudo -n systemctl restart agridoctor-core.service agridoctor-api.service
curl --fail --silent --show-error --retry 10 --retry-connrefused --retry-delay 1 \
    http://127.0.0.1:8765/health
printf '\n'
