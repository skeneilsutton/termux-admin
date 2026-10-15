#!/system/bin/sh
# Run only as Android shell. Commands and app authority stay in the signed APK.
set -u
[ "$(id -u)" = 2000 ] || exit 77
export PATH=/system/bin:/system/xbin
unset LD_PRELOAD LD_LIBRARY_PATH
BASE=/data/local/tmp/termux-admin-recovery
umask 077
mkdir -p "$BASE"
chmod 700 "$BASE" || exit 70
SSH_PORT=8022
if [ -f "$BASE/ssh-port" ]; then SSH_PORT=$(cat "$BASE/ssh-port"); fi
case "$SSH_PORT" in ''|*[!0-9]*) exit 64;; esac
[ "$SSH_PORT" -ge 1 ] && [ "$SSH_PORT" -le 65535 ] || exit 64
# Never unlink this file: all launchers must lock the same inode. The kernel
# releases the lock even after SIGKILL or reboot; PID metadata is diagnostic only.
exec 9>>"$BASE/watcher.lock" || exit 70
command -v flock >/dev/null 2>&1 || exit 70
# mksh marks persistent extra descriptors close-on-exec. Explicitly pass the
# descriptor to flock; the watcher keeps the same open-file description.
flock -n 9 9>&9 || exit 0
printf '%s\n' "$$" > "$BASE/watcher.pid"
printf '%s watcher started pid=%s\n' "$(date -Iseconds)" "$$" >>"$BASE/watch.log"
trap 'exit 0' HUP INT TERM
while :; do
 for log in "$BASE/shell.log" "$BASE/watch.log"; do
  if [ -f "$log" ] && [ "$(wc -c <"$log")" -gt 65536 ]; then
   tail -c 32768 "$log" >"$log.new" && cat "$log.new" >"$log"
   rm -f "$log.new"
  fi
 done
 worker=$(cat "$BASE/worker-com.termux.pid" 2>/dev/null)
 alive=0
 case "$worker" in
  ''|*[!0-9]*) ;;
  *) case " $(pidof termux_admin_shell 2>/dev/null) " in *" $worker "*) alive=1;; esac;;
 esac
 # Keep the host foreground before bootstrap. A leftover SSH session is not
 # proof that its listening server survived, and a cached host can be frozen.
 if [ "$alive" = 0 ] || ! ss -ltn 2>/dev/null | awk -v port="$SSH_PORT" '$1 == "LISTEN" && $4 ~ (":" port "$") { found=1 } END { exit !found }'; then
  apk=$(pm path --user 0 com.termux 2>/dev/null)
  case "$apk" in
   package:/data/app/*/base.apk)
    printf '%s ensuring foreground host and SSH listener\n' "$(date -Iseconds)" >>"$BASE/watch.log"
    CLASSPATH=${apk#package:} timeout -k 2 25 /system/bin/app_process / com.termux.admin.MaintenanceCli start-ssh 9>&- </dev/null >>"$BASE/watch.log" 2>&1
    ;;
  esac
 fi
 # A retired worker may still finish existing commands. Its PID must not stop
 # recovery of the new epoch. The marker is written only after registration.
 # pidof excludes unreaped zombies; kill -0 alone would treat them as alive.
 if [ "$alive" = 0 ]; then
  apk=$(pm path --user 0 com.termux 2>/dev/null)
  case "$apk" in
   package:/data/app/*/base.apk)
    printf '%s recovering shell worker\n' "$(date -Iseconds)" >>"$BASE/watch.log"
    CLASSPATH=${apk#package:} setsid /system/bin/app_process / --nice-name=termux_admin_shell \
      com.termux.admin.ShellService --recover 9>&- </dev/null >>"$BASE/shell.log" 2>&1 &
    ;;
  esac
 fi
 sleep 5 9>&-
done
