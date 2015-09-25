package de.olafkock.liferay.deploy.hot.delegating;

import com.liferay.portal.deploy.hot.HookHotDeployListener;
import com.liferay.portal.kernel.deploy.hot.BaseHotDeployListener;
import com.liferay.portal.kernel.deploy.hot.HotDeployEvent;
import com.liferay.portal.kernel.deploy.hot.HotDeployException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.security.pacl.PACLConstants;
import com.liferay.portal.kernel.security.pacl.permission.PortalHookPermission;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.HttpUtil;
import com.liferay.portal.kernel.util.StringBundler;
import com.liferay.portal.kernel.util.StringPool;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.kernel.xml.Document;
import com.liferay.portal.kernel.xml.Element;
import com.liferay.portal.kernel.xml.SAXReaderUtil;
import com.liferay.portal.struts.StrutsActionRegistryUtil;

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
	private Map<String, String> alreadyHookedActions = new HashMap<String, String>();
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
			ClassLoader portletClassLoader = hotDeployEvent.getContextClassLoader();

			_log.info("Invoking pre-deploy check for duplicate JSPs and Struts Actions in " + servletContextName);

			String xml = HttpUtil.URLtoString(servletContext
					.getResource("/WEB-INF/liferay-hook.xml"));
			if (xml == null) {
				return;
			}
			Document document = SAXReaderUtil.read(xml, true);
			Element rootElement = document.getRootElement();

			List<String> hookedJSPs = new LinkedList<String> ();
			List<String> hookedStrutsActions = new LinkedList<String>();
			
			if (!checkCustomJsp(servletContext, servletContextName, rootElement, hookedJSPs)) {
				rejectedServletContexts.add(servletContextName);
				_log.error( "Deployment of " + servletContextName
						  + " rejected due to JSP check failure. Check the log for duplicate hooked JSPs");
				return;
			} 
			if(!checkStrutsActions(portletClassLoader, rootElement, hookedStrutsActions)) {
				rejectedServletContexts.add(servletContextName);
				_log.error("Deployment of " + servletContextName
						+ " rejected due to duplicate Struts Action overloads. Check the log for duplicated Action overrides");
				return;
			}
		
			for(String hookedJSP: hookedJSPs) {
				alreadyHookedJSPs.put(hookedJSP, servletContextName);
			}
			for(String action: hookedStrutsActions) {
				alreadyHookedActions.put(action, servletContextName);
			}
			// all is well - let's delegate deployment to the original deployer.
			_log.info("Pre-deploy check for " + servletContextName + " succeeded. Delegating Deployment to original deployer");
			delegate.invokeDeploy(hotDeployEvent);

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
			keys = new ArrayList<String>(alreadyHookedActions.keySet());
			for(String action: keys) {
				if(alreadyHookedActions.get(action).equals(servletContextName)) {
					alreadyHookedActions.remove(action);
				}
			}
		}
	}

	protected boolean checkCustomJsp(ServletContext servletContext,
			String servletContextName, Element rootElement, List<String> collectHookedJSPs) {
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
				collectHookedJSPs.addAll(customJsps);
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
	
	/**
	 * Check if there is a conflict with already overridden struts actions in other hooks, as 
	 * well as for correct security manager declaration. 
	 * 
	 * @param servletContextName name of the currently checked servletContext
	 * @param portletClassLoader the plugin's portletClassLoader in order to invoke security checks
	 * @param parentElement the element to iterate from liferay-hook.xml
	 * @param collectHookedStrutsActions name of all struts actions, use only when this method returns true
	 * @return true if there are no conflicts and this hook can be deployed
	 */
	protected boolean checkStrutsActions(
			ClassLoader portletClassLoader,	Element parentElement, 
			List<String>collectHookedStrutsActions) {

		List<Element> strutsActionElements = parentElement.elements(
				"struts-action");
		boolean result = true;
		
		for (Element strutsActionElement : strutsActionElements) {
			String strutsActionPath = strutsActionElement.elementText(
				"struts-action-path");

			if (!checkPermission(
					PACLConstants.PORTAL_HOOK_PERMISSION_STRUTS_ACTION_PATH,
					portletClassLoader, strutsActionPath,
					"Rejecting struts action path " + strutsActionPath)) {

				result = false;
			}

			if(StrutsActionRegistryUtil.getAction(strutsActionPath) != null) {
				_log.error("Struts action " + strutsActionPath + " already overloaded in hook " +
						alreadyHookedActions.get(strutsActionPath));
				result = false;
			}

			collectHookedStrutsActions.add(strutsActionPath);
			
		}

		return result;
	}
	
	protected boolean checkPermission(
		String name, ClassLoader portletClassLoader, Object subject,String message) {

		try {
			PortalHookPermission.checkPermission(name, portletClassLoader, subject);
		}
		catch (SecurityException se) {
			_log.error(message);
			return false;
		}
		return true;
	}

	
	private static Log _log = LogFactoryUtil.getLog(CheckingHookHotDeployListener.class);

}
