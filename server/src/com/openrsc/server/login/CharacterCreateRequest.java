package com.openrsc.server.login;

import com.openrsc.server.Server;
import com.openrsc.server.database.GameDatabaseException;
import com.openrsc.server.database.struct.PlayerLoginData;
import com.openrsc.server.net.PacketBuilder;
import com.openrsc.server.util.rsc.DataConversions;
import com.openrsc.server.util.rsc.RegisterResponse;
import io.netty.channel.Channel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Used to create a Character on the Login thread
 */
public class CharacterCreateRequest extends LoginExecutorProcess{

	/**
	 * The asynchronous logger.
	 */
	private static final Logger LOGGER = LogManager.getLogger();

	private final Server server;
	private String ipAddress;
	private String username;
	private String password;
	private String email;
	private int clientVersion;
	private boolean authenticClient;
	private Channel channel;

	public CharacterCreateRequest(final Server server, final Channel channel, final String username, final String password, final boolean isAuthenticClient, final int clientVersion) {
		this.server = server;
		this.setEmail("");
		this.setUsername(username);
		this.setPassword(password);
		this.setAuthenticClient(isAuthenticClient);
		this.setChannel(channel);
		this.setIpAddress(getChannel().remoteAddress().toString());
		this.setClientVersion(clientVersion);
	}

	public CharacterCreateRequest(final Server server, final Channel channel, final String username, final String password, final String email, final int clientVersion) {
		this.server = server;
		this.setEmail(email);
		this.setUsername(username);
		this.setPassword(password);
		this.setAuthenticClient(false);
		this.setChannel(channel);
		this.setIpAddress(getChannel().remoteAddress().toString());
		this.setClientVersion(clientVersion);
	}

	public String getIpAddress() {
		return ipAddress;
	}

	private void setIpAddress(final String ipAddress) {
		this.ipAddress = ipAddress;
	}

	public String getUsername() {
		return username;
	}

	private void setUsername(final String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	private void setPassword(final String password) {
		this.password = password;
	}

	public boolean getAuthenticClient() {
		return authenticClient;
	}

	private void setAuthenticClient(final boolean authenticClient) {
		this.authenticClient = authenticClient;
	}

	public Channel getChannel() {
		return channel;
	}

	private void setChannel(final Channel channel) {
		this.channel = channel;
	}

	public String getEmail() {
		return email;
	}

	private void setEmail(final String email) {
		this.email = email;
	}

	public Server getServer() {
		return server;
	}

	public int getClientVersion() {
		return clientVersion;
	}

	private void setClientVersion(final int clientVersion) {
		this.clientVersion = clientVersion;
	}

	protected void processInternal() {
		if (getAuthenticClient()) {
			final int registerResponse = validateRegister();
			getChannel().writeAndFlush(new PacketBuilder().writeByte((byte) registerResponse).toPacket());
			if (registerResponse != RegisterResponse.REGISTER_SUCCESSFUL) {
				getChannel().close();
			}
			LOGGER.info("Processed register request for " + getUsername() + " response: " + registerResponse);
		} else {
			try {
				if (getUsername().length() < 2 || getUsername().length() > 12) {
					getChannel().writeAndFlush(new PacketBuilder().writeByte((byte) 7).toPacket());
					getChannel().close();
					return;
				}

				if (!getServer().getConfig().CHAR_NAME_CAN_CONTAIN_MOD
					&& (getUsername().toLowerCase().startsWith("mod")
					|| getUsername().toLowerCase().startsWith("m0d"))) {

					getChannel().writeAndFlush(new PacketBuilder().writeByte((byte) 8).toPacket());
					getChannel().close();
					return;
				}

				if (getPassword().length() < 4 || getPassword().length() > 64) {
					getChannel().writeAndFlush(new PacketBuilder().writeByte((byte) 8).toPacket());
					getChannel().close();
					return;
				}

				if (getServer().getConfig().WANT_EMAIL) {
					if (!DataConversions.isValidEmailAddress(email)) {
						getChannel().writeAndFlush(new PacketBuilder().writeByte((byte) 6).toPacket());
						getChannel().close();
						return;
					}
				}

				if (getServer().getConfig().WANT_REGISTRATION_LIMIT) {
					boolean recentlyRegistered = getServer().getDatabase().checkRecentlyRegistered(getIpAddress());
					if (recentlyRegistered) {
						LOGGER.info(getIpAddress() + " - Registration failed: Registered recently.");
						getChannel().writeAndFlush(new PacketBuilder().writeByte((byte) 5).toPacket());
						getChannel().close();
						return;
					}
				}

				boolean usernameExists = getServer().getDatabase().playerExists(getUsername());
				if (usernameExists) {
					LOGGER.info(getIpAddress() + " - Registration failed: Forum Username already in use.");
					getChannel().writeAndFlush(new PacketBuilder().writeByte((byte) 2).toPacket());
					getChannel().close();
					return;
				}

				/* Create the game character */
				final int playerId = getServer().getDatabase().createPlayer(getUsername(), getEmail(),
					DataConversions.hashPassword(getPassword(), null),
					System.currentTimeMillis() / 1000, getIpAddress());

				if (playerId == -1) {
					LOGGER.info(getIpAddress() + " - Registration failed: Player id not found.");
					getChannel().writeAndFlush(new PacketBuilder().writeByte((byte) 6).toPacket());
					getChannel().close();
					return;
				}

				LOGGER.info(getIpAddress() + " - Registration successful");
				getChannel().writeAndFlush(new PacketBuilder().writeByte((byte) 0).toPacket());
			} catch (Exception e) {
				LOGGER.catching(e);
				getChannel().writeAndFlush(new PacketBuilder().writeByte((byte) 5).toPacket());
				getChannel().close();
			}
		}
	}

	public byte validateRegister() {
		PlayerLoginData playerData;
		try {
			playerData = getServer().getDatabase().getPlayerLoginData(username);

			boolean isAdmin = getServer().getPacketFilter().isHostAdmin(getIpAddress());

			if (getServer().getPacketFilter().getPasswordAttemptsCount(getIpAddress()) >= getServer().getConfig().MAX_PASSWORD_GUESSES_PER_FIVE_MINUTES && !isAdmin) {
				return (byte) RegisterResponse.LOGIN_ATTEMPTS_EXCEEDED;
			}

			if (getServer().getPacketFilter().isHostIpBanned(getIpAddress()) && !isAdmin) {
				return (byte) RegisterResponse.ACCOUNT_TEMP_DISABLED;
			}

			if (getClientVersion() != getServer().getConfig().CLIENT_VERSION && !isAdmin && getClientVersion() != 235) {
				return (byte) RegisterResponse.CLIENT_UPDATED;
			}

			if(getServer().getWorld().getPlayers().size() >= getServer().getConfig().MAX_PLAYERS && !isAdmin) {
				return (byte) RegisterResponse.WORLD_IS_FULL;
			}

			if (getServer().getDatabase().playerExists(getUsername())) {
				return (byte) RegisterResponse.USERNAME_TAKEN;
			}

			if (getServer().getWorld().getPlayer(DataConversions.usernameToHash(getUsername())) != null) {
				return (byte) RegisterResponse.ACCOUNT_LOGGEDIN;
			}

			if(getServer().getPacketFilter().getPlayersCount(getIpAddress()) >= getServer().getConfig().MAX_PLAYERS_PER_IP && !isAdmin) {
				return (byte) RegisterResponse.IP_IN_USE;
			}

			final long banExpires = playerData != null ? playerData.banned : 0;
			if (banExpires == -1 && !isAdmin) {
				return (byte) RegisterResponse.ACCOUNT_PERM_DISABLED;
			}

			final double timeBanLeft = (double) (banExpires - System.currentTimeMillis());
			if (timeBanLeft >= 1 && !isAdmin) {
				return (byte) RegisterResponse.ACCOUNT_TEMP_DISABLED;
			}

			if (!getServer().getConfig().CHAR_NAME_CAN_CONTAIN_MOD
				&& (getUsername().toLowerCase().startsWith("mod")
				|| getUsername().toLowerCase().startsWith("m0d"))) {
				return (byte) RegisterResponse.USERNAME_TAKEN_DISALLOWED;
			}

			if (getServer().getConfig().WANT_REGISTRATION_LIMIT) {
				boolean recentlyRegistered = getServer().getDatabase().checkRecentlyRegistered(getIpAddress());
				if (recentlyRegistered) {
					LOGGER.info(getIpAddress() + " - Registration failed: Registered recently.");
					return (byte) RegisterResponse.LOGIN_ATTEMPTS_EXCEEDED; // closest match for authentic client
				}
			}

			/* Create the game character */
			final int playerId = getServer().getDatabase().createPlayer(getUsername(), getEmail(),
				DataConversions.hashPassword(getPassword(), null),
				System.currentTimeMillis() / 1000, getIpAddress());

			if (playerId == -1) {
				LOGGER.info(getIpAddress() + " - Registration failed: Player id not found.");
				return (byte) RegisterResponse.REGISTER_UNSUCCESSFUL;
			}
		} catch (GameDatabaseException e) {
			LOGGER.catching(e);
			return (byte) RegisterResponse.REGISTER_UNSUCCESSFUL;
		}
		return (byte) RegisterResponse.REGISTER_SUCCESSFUL;
	}
}
