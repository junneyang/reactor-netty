package reactor.netty.transport;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import javax.annotation.Nullable;

import io.netty.util.NetUtil;
import reactor.core.CoreSubscriber;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoOperator;
import reactor.netty.transport.AddressUtils.MonoSocketAddress;

/**
 * Internal class that creates unresolved or resolved InetSocketAddress instances
 *
 * Numeric IPv4 and IPv6 addresses will be detected and parsed by using Netty's
 * {@link NetUtil#createByteArrayFromIpAddressString} utility method and the
 * InetSocketAddress instances will created in a way that these instances are resolved
 * initially. This removes the need to do unnecessary reverse DNS lookups.
 */
final public class AddressUtils {

	/**
	 * Creates InetSocketAddress instance. Numeric IP addresses will be detected and resolved without doing reverse DNS
	 * lookups.
	 *
	 * @param hostname ip-address or hostname
	 * @param port port number
	 * @param resolve when true, resolve given hostname at instance creation time
	 *
	 * @return InetSocketAddress for given parameters
	 */
	public static InetSocketAddress createInetSocketAddress(String hostname, int port, boolean resolve) {
		InetSocketAddress inetAddressForIpString = createForIpString(hostname, port);
		if (inetAddressForIpString != null) {
			return inetAddressForIpString;
		}
		else {
			return resolve ? new InetSocketAddress(hostname, port) : InetSocketAddress.createUnresolved(hostname, port);
		}
	}

	/**
	 * Creates InetSocketAddress that is always resolved. Numeric IP addresses will be detected and resolved without
	 * doing reverse DNS lookups.
	 *
	 * @param hostname ip-address or hostname
	 * @param port port number
	 *
	 * @return InetSocketAddress for given parameters
	 */
	public static InetSocketAddress createResolved(String hostname, int port) {
		return createInetSocketAddress(hostname, port, true);
	}

	/**
	 * Creates unresolved InetSocketAddress. Numeric IP addresses will be detected and resolved.
	 *
	 * @param hostname ip-address or hostname
	 * @param port port number
	 *
	 * @return InetSocketAddress for given parameters
	 */
	public static InetSocketAddress createUnresolved(String hostname, int port) {
		return createInetSocketAddress(hostname, port, false);
	}

	/**
	 * Replaces an unresolved InetSocketAddress with a resolved instance in the case that the passed address is a
	 * numeric IP address (both IPv4 and IPv6 are supported).
	 *
	 * @param inetSocketAddress socket address instance to process
	 *
	 * @return processed socket address instance
	 */
	public static InetSocketAddress replaceUnresolvedNumericIp(InetSocketAddress inetSocketAddress) {
		if (!inetSocketAddress.isUnresolved()) {
			return inetSocketAddress;
		}
		InetSocketAddress inetAddressForIpString =
				createForIpString(inetSocketAddress.getHostString(), inetSocketAddress.getPort());
		if (inetAddressForIpString != null) {
			return inetAddressForIpString;
		}
		else {
			return inetSocketAddress;
		}
	}

	/**
	 * Replaces an unresolved InetSocketAddress with a resolved instance in the case that the passed address is
	 * unresolved.
	 *
	 * @param inetSocketAddress socket address instance to process
	 *
	 * @return resolved instance with same host string and port
	 */
	public static InetSocketAddress replaceWithResolved(InetSocketAddress inetSocketAddress) {
		if (!inetSocketAddress.isUnresolved()) {
			return inetSocketAddress;
		}
		inetSocketAddress = replaceUnresolvedNumericIp(inetSocketAddress);
		if (!inetSocketAddress.isUnresolved()) {
			return inetSocketAddress;
		}
		else {
			return new InetSocketAddress(inetSocketAddress.getHostString(), inetSocketAddress.getPort());
		}
	}

	static final class SocketAddressSupplier extends SocketAddress implements Supplier<SocketAddress> {

		final Supplier<? extends SocketAddress> supplier;

		SocketAddressSupplier(Supplier<? extends SocketAddress> supplier) {
			this.supplier = Objects.requireNonNull(supplier, "Lazy address supplier must not be null");
		}

		@Override
		public SocketAddress get() {
			return supplier.get();
		}
	}

	static final class MonoSocketAddress extends MonoOperator<SocketAddress, SocketAddress> {

		MonoSocketAddress(Mono<? extends SocketAddress> source) {
			super(source);
		}

		@Override
		public void subscribe(CoreSubscriber<? super SocketAddress> actual) {
			source.subscribe(actual);
		}
	}

	@Nullable
	static InetAddress attemptParsingIpString(String hostname) {
		byte[] ipAddressBytes = NetUtil.createByteArrayFromIpAddressString(hostname);

		if (ipAddressBytes != null) {
			try {
				if (ipAddressBytes.length == 4) {
					return Inet4Address.getByAddress(ipAddressBytes);
				}
				else {
					return Inet6Address.getByAddress(null, ipAddressBytes, -1);
				}
			}
			catch (UnknownHostException e) {
				throw new RuntimeException(e); // Should never happen
			}
		}

		return null;
	}

	@Nullable
	static InetSocketAddress createForIpString(String hostname, int port) {
		InetAddress inetAddressForIpString = attemptParsingIpString(hostname);
		if (inetAddressForIpString != null) {
			return new InetSocketAddress(inetAddressForIpString, port);
		}
		return null;
	}

	static SocketAddress updateHost(@Nullable SocketAddress address, String host) {
		Objects.requireNonNull(host, "host");
		if (!(address instanceof InetSocketAddress)) {
			return createUnresolved(host, 0);
		}

		InetSocketAddress inet = (InetSocketAddress) address;

		return createUnresolved(host, inet.getPort());
	}

	static SocketAddress updatePort(@Nullable SocketAddress address, int port) {
		if (!(address instanceof InetSocketAddress)) {
			return createUnresolved(NetUtil.LOCALHOST.getHostAddress(), port);
		}

		InetSocketAddress inet = (InetSocketAddress) address;

		InetAddress addr = inet.getAddress();

		String host = addr == null ? inet.getHostName() : addr.getHostAddress();

		return createUnresolved(host, port);
	}

	@SuppressWarnings("unchecked")
	static MonoSocketAddress lazyAddress(Mono<? extends SocketAddress> supplier) {
		if (supplier instanceof Callable) {
			return new MonoSocketAddress(() -> {
				try {
					return ((Callable<SocketAddress>) supplier).call();
				}
				catch (Exception e) {
					throw Exceptions.propagate(e);
				}
			});
		}
		return new MonoSocketAddress(supplier);
	}
}