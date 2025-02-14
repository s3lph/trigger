package app.trigger.ssh

import app.trigger.Utils.isEmpty
import app.trigger.DoorReply.ReplyCode
import com.trilead.ssh2.crypto.PEMDecoder
import com.trilead.ssh2.crypto.keys.Ed25519Provider
import app.trigger.*
import com.trilead.ssh2.*
import java.io.IOException
import java.lang.Exception
import java.net.SocketTimeoutException
import java.security.KeyPair


class SshRequestHandler(private val listener: OnTaskCompleted, private val setup: SshDoorSetup, private val action: MainActivity.Action) : Thread(), ConnectionMonitor {
    companion object {
        private const val TAG = "SshRequestHandler"
        private const val conditions = (ChannelCondition.STDOUT_DATA
                or ChannelCondition.STDERR_DATA
                or ChannelCondition.CLOSED
                or ChannelCondition.EOF)

        fun testPassphrase(kp: KeyPairBean?, passphrase: String): Boolean {
            return kp != null && decodeKeyPair(kp, passphrase) != null
        }

        private fun decodeKeyPair(kp: KeyPairBean, passphrase: String): KeyPair? {
            return if (KeyPairBean.KEY_TYPE_IMPORTED == kp.type) {
                // load specific key using pem format
                PEMDecoder.decode(String(kp.privateKey, Charsets.UTF_8).toCharArray(), passphrase)
            } else {
                // load using internal generated format
                val privKey = try {
                    PubkeyUtils.decodePrivate(kp.privateKey, kp.type, passphrase)
                } catch (e: Exception) {
                    Log.e(TAG, "Bad passphrase for key. Authentication failed: $e")
                    return null
                }
                val pubKey = PubkeyUtils.decodePublic(kp.publicKey, kp.type)

                // convert key to trilead format
                KeyPair(pubKey, privKey)
            }
        }

        private fun read(session: Session?, buffer: ByteArray, start: Int, len: Int, timeout_ms: Int): Int {
            var bytesRead = 0
            if (session == null) return 0
            val stdout = session.stdout
            val stderr = session.stderr
            val newConditions = session.waitForCondition(conditions, timeout_ms.toLong())

            if (newConditions and ChannelCondition.STDOUT_DATA != 0) {
                bytesRead = stdout.read(buffer, start, len)
            }

            if (newConditions and ChannelCondition.STDERR_DATA != 0) {
                val discard = ByteArray(256)
                while (stderr.available() > 0) {
                    stderr.read(discard)
                    //Log.e(TAG, "stderr: " + (new String(discard)));
                }
            }

            if (newConditions and ChannelCondition.EOF != 0) {
                throw IOException("Remote end closed connection")
            }

            return bytesRead
        }

        private fun write(session: Session, command: String) {
            val stdin = session.stdin
            stdin?.write(command.toByteArray())
        }

        init {
            Log.d(TAG, "Ed25519Provider.insertIfNeeded2")
            // Since this class deals with Ed25519 keys, we need to make sure this is available.
            Ed25519Provider.insertIfNeeded()
        }
    }

    override fun run() {
        if (setup.id < 0) {
            listener.onTaskResult(setup.id, ReplyCode.LOCAL_ERROR, "Internal Error")
            return
        }

        val command = when (action) {
            MainActivity.Action.open_door -> setup.open_command
            MainActivity.Action.close_door -> setup.close_command
            MainActivity.Action.ring_door -> setup.ring_command
            MainActivity.Action.fetch_state -> setup.state_command
        }

        val username = if (isEmpty(setup.user)) "root" else setup.user
        val password = setup.password
        val hostname = setup.host
        val keypair = setup.keypair
        val port = setup.port

        if (command.isEmpty()) {
            listener.onTaskResult(setup.id, ReplyCode.LOCAL_ERROR, "")
            return
        }

        if (hostname.isEmpty()) {
            listener.onTaskResult(setup.id, ReplyCode.LOCAL_ERROR, "Server address is empty.")
            return
        }

        var connection: Connection? = null
        var session : Session? = null
        try {
            connection = Connection(hostname, port)
            connection.addConnectionMonitor(this)

            val connectionInfo = connection.connect(
                null,  // host key verifier
                2000,  // connect timeout
                3000 // key exchange timeout
            )

            // authentication by key pair
            if (keypair != null) {
                val kp = decodeKeyPair(keypair, setup.passphrase_tmp)
                if (kp != null) {
                    if (connection.authenticateWithPublicKey(username, kp)) {
                        // login successful
                    } else {
                        listener.onTaskResult(setup.id, ReplyCode.REMOTE_ERROR, "Key was not accepted.")
                        return
                    }
                } else {
                    if (keypair.encrypted) {
                        setup.passphrase_tmp = "" // reset (incorrect) passphrase
                        listener.onTaskResult(setup.id, ReplyCode.LOCAL_ERROR, "Key pair passphrase was not accepted.")
                        return
                    } else {
                        listener.onTaskResult(setup.id, ReplyCode.LOCAL_ERROR, "Failed to decode key pair.")
                        return
                    }
                }
            }

            // authentication by password
            else if (password.isNotEmpty()) {
                if (connection.isAuthMethodAvailable(username, "password")) {
                    if (connection.authenticateWithPassword(username, password)) {
                        // login successful
                    } else {
                        listener.onTaskResult(setup.id, ReplyCode.REMOTE_ERROR, "Password was not accepted.")
                        return
                    }
                } else {
                    listener.onTaskResult(setup.id, ReplyCode.REMOTE_ERROR, "Host does not support password authentication.")
                    return
                }
            }

            // try without authentication
            else if (password.isEmpty()) {
                if (connection.authenticateWithNone(username)) {
                    // login successful
                } else {
                    listener.onTaskResult(setup.id, ReplyCode.REMOTE_ERROR, "Login without any credentials failed.")
                    return
                }
            }

            // final check
            if (!connection.isAuthenticationComplete) {
                listener.onTaskResult(setup.id, ReplyCode.REMOTE_ERROR, "Authentication failed.")
                return
            }

            session = connection.openSession()
            val buffer = ByteArray(1000)

            // clear any welcome message (stdout/stderr)
            //read(session, buffer, 0, buffer.length);
            session.execCommand(command)

            // read stdout (drop stderr)
            val bytes_read = read(session, buffer, 0, buffer.size, setup.timeout)
            val output = String(buffer, 0, bytes_read)
            val ret = session.exitStatus
            if (ret == null || ret == 0) {
                listener.onTaskResult(setup.id, ReplyCode.SUCCESS, output)
            } else {
                listener.onTaskResult(setup.id, ReplyCode.REMOTE_ERROR, output)
            }
        } catch (e: SocketTimeoutException) {
            listener.onTaskResult(setup.id, ReplyCode.LOCAL_ERROR, "Connection timeout. Connected to the right network?")
        } catch (e: Exception) {
            listener.onTaskResult(setup.id, ReplyCode.LOCAL_ERROR, e.message!!)
            Log.e(TAG, "Problem in SSH connection thread during authentication: $e")
        } finally {
            session?.close()
            connection?.close()
        }
    }

    override fun connectionLost(reason: Throwable) {
        Log.d(TAG, "connectionLost")
    }
}