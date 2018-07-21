package js.soap;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletConfig;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import js.container.AuthorizationException;
import js.container.ManagedMethodSPI;
import js.dom.Document;
import js.dom.DocumentBuilder;
import js.http.HttpHeader;
import js.http.Resource;
import js.http.encoder.ServerEncoders;
import js.lang.BugError;
import js.lang.IllegalArgumentException;
import js.log.Log;
import js.log.LogFactory;
import js.servlet.AppServlet;
import js.servlet.RequestContext;
import js.util.Files;
import js.util.Strings;
import js.util.Types;

/**
 * Incoming requests handler for SOAP requests.
 * 
 * @author Iulian Rotaru
 * @version experimental
 */
public final class SoapServlet extends AppServlet {
	/** Java serialization version. */
	private static final long serialVersionUID = -858526018624346584L;

	/** Class logger. */
	private static final Log log = LogFactory.getLog(SoapServlet.class);

	/**
	 * SOAP request pattern. <em>SOAP</em> request has only fully qualified class name that implements the service, case
	 * sensitive and separated by '/', see BNF below:
	 * 
	 * <pre>
	 *    soap-request = qualified-class-name '/' 
	 *    qualified-class-name = package-name '/' class-name
	 *    package-name = 'a'..'z' *( 'a'..'z' / '0'..'9' / '_' ) [ '/' package-name ]
	 *    class-name = 'A'..'Z' *( 'a'..'z' / 'A'..'Z' / '0'.. '9' / '_' )
	 * </pre>
	 */
	private static final Pattern SOAP_REQUEST_PATTERN = Pattern.compile("^(\\/[a-z][a-z0-9]*(?:\\/[a-z][a-z0-9]*)*(?:\\/[A-Z][a-zA-Z0-9_]*)+)\\/$");

	private final Map<String, ManagedMethodSPI> methods = new HashMap<>();

	public SoapServlet() {
		log.trace("SoapServlet()");
	}

	@Override
	public void init(ServletConfig config) throws UnavailableException {
		super.init(config);
		for (ManagedMethodSPI managedMethod : container.getManagedMethods()) {
			if (!managedMethod.isRemotelyAccessible()) {
				continue;
			}
			if (managedMethod.getDeclaringClass().getInterfaceClasses().length != 1) {
				continue;
			}
			if (!Types.isKindOf(managedMethod.getReturnType(), Resource.class)) {
				methods.put(getStorageKey(managedMethod), managedMethod);
			}
		}
	}

	/**
	 * HTTP-RMI request service. This is the implementation of the {@link AppServlet#handleRequest(RequestContext)} abstract
	 * method. It locates remote managed method addressed by request path, deserialize actual parameters from HTTP request,
	 * reflexively invoke the method and serialize method returned value to HTTP response.
	 * <p>
	 * Actual parameters are transported into HTTP request body and are encoded accordingly <code>Content-Type</code> request
	 * header; for supported parameters encodings please see
	 * {@link ServerEncoders#getArgumentsReader(HttpServletRequest, Type[])} factory method. On its side, returned value is
	 * transported into HTTP response body too and is encoded accordingly its type - see
	 * {@link ServerEncoders#getValueWriter(js.http.encoder.MimeType)} for supported encodings for returned value.
	 * <p>
	 * Please note that, consistent with this library, both parameters and return value use <code>UTF-8</code> charset.
	 * 
	 * @param context request context.
	 * @throws IOException if HTTP request reading or response writing fail for any reason.
	 */
	@Override
	public void handleRequest(RequestContext context) throws IOException {
		ManagedMethodSPI managedMethod = null;
		Object value = null;

		try {
			final HttpServletRequest request = context.getRequest();
			Matcher matcher = SOAP_REQUEST_PATTERN.matcher(context.getRequestPath());
			if (!matcher.find()) {
				throw new ClassNotFoundException(request.getRequestURI());
			}
			String classPath = matcher.group(1);
			String methodName = Strings.trim(request.getHeader(HttpHeader.SOAP_ACTION), '"');

			String requestPath = Strings.concat(classPath, '/', methodName);
			managedMethod = methods.get(getRetrievalKey(requestPath));

			Type[] formalParameters = managedMethod.getParameterTypes();
			if (formalParameters.length > 1) {
				log.debug("Invalid SOAP request. Bad parameters count. Method must have one single formal parameter.");
				throw new IllegalArgumentException(formalParameters);
			}

			// we don't expect generic types so next cast is pretty safe
			Class<?> type = (Class<?>) formalParameters[0];
			Object[] parameters = new Object[1];

			if (Types.isKindOf(type, Document.class)) {
				DocumentBuilder builder = container.getInstance(DocumentBuilder.class);
				parameters[0] = builder.loadXML(request.getInputStream());
			} else if (Types.isKindOf(type, InputStream.class)) {
				parameters[0] = request.getInputStream();
			} else {
				log.debug(String.format("Invalid SOAP request. Unrecognized formal parameter type |%s|.", type));
				throw new IllegalArgumentException(formalParameters);
			}

			Object instance = container.getInstance(managedMethod.getDeclaringClass());
			value = managedMethod.invoke(instance, parameters);

			if (parameters[0] instanceof Closeable) {
				((Closeable) parameters[0]).close();
			}
		} catch (AuthorizationException e) {
			// TODO investigate alternative to send SOAP response with error code
			sendUnauthorized(context);
			return;
		} catch (Throwable t) {
			sendError(context, t);
			return;
		}

		final HttpServletResponse response = context.getResponse();
		response.setCharacterEncoding("UTF-8");
		response.setStatus(HttpServletResponse.SC_OK);
		if (managedMethod.isVoid()) {
			assert value == null;
			response.setContentLength(0);
			return;
		}

		throw new BugError("Non void SOAP method not yet implemented.");
	}

	// --------------------------------------------------------------------------------------------
	// UTILITY METHODS

	private static String getStorageKey(ManagedMethodSPI managedMethod) {
		StringBuilder requestPath = new StringBuilder();
		requestPath.append('/');
		requestPath.append(Files.dot2urlpath(managedMethod.getDeclaringClass().getInterfaceClass().getName()));
		requestPath.append('/');
		requestPath.append(managedMethod.getMethod().getName());
		return requestPath.toString();
	}

	private static String getRetrievalKey(String requestPath) {
		int queryParametersIndex = requestPath.lastIndexOf('?');
		if (queryParametersIndex == -1) {
			queryParametersIndex = requestPath.length();
		}
		int extensionIndex = requestPath.lastIndexOf('.', queryParametersIndex);
		if (extensionIndex == -1) {
			extensionIndex = requestPath.length();
		}
		return requestPath.substring(0, extensionIndex);
	}
}
