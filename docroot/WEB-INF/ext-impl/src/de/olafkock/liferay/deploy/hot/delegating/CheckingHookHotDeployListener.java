package de.olafkock.liferay.deploy.hot.delegating;

import com.liferay.portal.deploy.hot.HookHotDeployListener;
import com.liferay.portal.kernel.deploy.hot.BaseHotDeployListener;
import com.liferay.portal.kernel.deploy.hot.HotDeployEvent;
import com.liferay.portal.kernel.deploy.hot.HotDeployException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.HttpUtil;
import com.liferay.portal.kernel.util.StringBundler;
import com.liferay.portal.kernel.util.StringPool;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.kernel.xml.Document;
import com.liferay.portal.kernel.xml.Element;
import com.liferay.portal.kernel.xml.SAXReaderUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletContext;

/**
 * Liferay requires (but doesn't enforce) that every JSP that is hooked is only hooked by
 * a single plugin - otherwise the installation might be damaged and the original JSP 
 * being lost. As the hook deployment order is undetermined, it would also result in
 * undefined behavior anyway.
 * 
 * This hook predeployment check makes sure that each JSP in Liferay is only hooked once
 * and will reject any subsequent hook that changes a JSP that has already been changed
 * from another plugin.
 * 
 * In order to work around this blocking, the administrator has to manually resolve the
 * problem - but at least failure now is implicit and doesn't secretly delete files from
 * the implementation.
 * 
 * As this implementation must be available before any hook is deployed (and hook 
 * deployment order is not guaranteed), it must be installed as an ext-plugin.
 * 
 * Note: This is experimental code. Feedback and pullrequests welcome. No responsibility
 * assumed. Use at your own risk.
 * 
 * @author Olaf Kock
 */

public class CheckingHookHotDeployListener extends BaseHotDeployListener {

	private static List<String> rejectionProtocol = new LinkedList<String>();

	private HookHotDeployListener delegate = new HookHotDeployListener();
	private Map<String, String> alreadyHookedJSPs = new HashMap<String, String>();
	private Set<String> rejectedServletContexts = new HashSet<String>();

	/**
	 * The hook deployer keeps record of rejected hooks. This can be shown in the UI
	 * if necessary. This is a memory leak, but naturally there will be limited numbers
	 * of hook deployments and limited number of JSPs per hook, so it can't be bad. 
	 * 
	 * @return an unmodifiable list rejected servlet context and JSP names
	 */
	
	public static Collection<String> getRejectionProtocol() {
		return Collections.unmodifiableCollection(rejectionProtocol);
	}
	
	/**
	 * clear the messages of rejected servlet contexts and JSPs
	 */
	public static void clearRejectionProtocol() {
		rejectionProtocol.clear();
	}
	
	@Override
	public void invokeDeploy(HotDeployEvent hotDeployEvent)
			throws HotDeployException {

		try {
			ServletContext servletContext = hotDeployEvent.getServletContext();
			String servletContextName = servletContext.getServletContextName();

			_log.info("Invoking pre-deploy check for duplicate JSPs in " + servletContextName);

			String xml = HttpUtil.URLtoString(servletContext
					.getResource("/WEB-INF/liferay-hook.xml"));
			if (xml == null) {
				return;
			}
			Document document = SAXReaderUtil.read(xml, true);
			Element rootElement = document.getRootElement();

			if (!checkCustomJsp(servletContext, servletContextName, rootElement)) {
				rejectedServletContexts.add(servletContextName);
				_log.error( "Deployment of " + servletContextName
						  + " rejected due to JSP check failure. Check the log for duplicate hooked JSPs");
			} else {
				_log.info("Pre-deploy check for " + servletContextName + " succeeded. Delegating Deployment to original deployer");
				delegate.invokeDeploy(hotDeployEvent);
			}
		} catch (Throwable t) {
			throwHotDeployException(
					hotDeployEvent,
					"Error registering hook for "
							+ hotDeployEvent.getServletContextName(), t);
		}
	}

	@Override
	public void invokeUndeploy(HotDeployEvent hotDeployEvent)
			throws HotDeployException {

		String servletContextName = hotDeployEvent.getServletContextName();
		if(rejectedServletContexts.contains(servletContextName)) {
			rejectedServletContexts.remove(servletContextName);
			_log.error("skipping undeploy for hook " + servletContextName + 
					" as its deployment has been rejected");
		} else {
			delegate.invokeUndeploy(hotDeployEvent);
			
			// Should be done after undeployment - not sure what to do in 
			// event of an exception... leaving unhandled for now: Better 
			// keep rejecting if an exception occurs as the JSPs might
			// still be there. Better safe than sorry...
			List<String> keys = new ArrayList<String>(alreadyHookedJSPs.keySet());
			for(String jsp : keys) {
				if(alreadyHookedJSPs.get(jsp).equals(servletContextName)) {
					alreadyHookedJSPs.remove(jsp);
				}
			}
		}
	}

	protected boolean checkCustomJsp(ServletContext servletContext,
			String servletContextName, Element rootElement) {
		String customJspDir = rootElement.elementText("custom-jsp-dir");

		if (Validator.isNull(customJspDir)) {
			return true;
		}

		_log.debug("Custom JSP directory: " + customJspDir);

		boolean customJspGlobal = GetterUtil.getBoolean(
				rootElement.elementText("custom-jsp-global"), true);

		List<String> customJsps = new ArrayList<String>();

		getCustomJsps(servletContext, customJspDir, customJspDir, customJsps);

		if (customJsps.isEmpty()) {
			return true;
		}

		if (_log.isDebugEnabled()) {
			StringBundler sb = new StringBundler(customJsps.size() * 2 + 1);

			sb.append("Custom JSP files:\n");

			for (String customJsp : customJsps) {
				sb.append(customJsp);
				sb.append(StringPool.NEW_LINE);
			}

			sb.setIndex(sb.index() - 1);

			_log.debug(sb.toString());
		}

		boolean result = true;

		if (customJspGlobal) {
			for (String customJsp : customJsps) {
				String alreadyHookedInContext = alreadyHookedJSPs
						.get(customJsp);
				if (alreadyHookedInContext != null) {
					rejectionProtocol.add(servletContextName + " - " + customJsp);
					_log.error("Duplicate jsp hook for " + customJsp + " from "
							+ servletContextName
							+ ". It has already been hooked by "
							+ alreadyHookedInContext);
					result = false;
				}
			}
			if (result) {
				for (String customJsp : customJsps) {
					alreadyHookedJSPs.put(customJsp, servletContextName);
				}
			}
		}

		return result;
	}

	protected void getCustomJsps(ServletContext servletContext, String rootDir,
			String resourcePath, List<String> customJsps) {

		Set<String> resourcePaths = servletContext
				.getResourcePaths(resourcePath);

		if ((resourcePaths == null) || resourcePaths.isEmpty()) {
			return;
		}

		for (String curResourcePath : resourcePaths) {
			if (curResourcePath.endsWith(StringPool.SLASH)) {
				getCustomJsps(servletContext, rootDir, curResourcePath,
						customJsps);
			} else {
				String customJsp = curResourcePath;

				customJsp = StringUtil.replace(customJsp,
						StringPool.DOUBLE_SLASH, StringPool.SLASH);
				customJsp = customJsp.substring(rootDir.length());
				customJsps.add(customJsp);
			}
		}
	}

	private static Log _log = LogFactoryUtil.getLog(CheckingHookHotDeployListener.class);

}
