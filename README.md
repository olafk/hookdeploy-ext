# hookdeploy-ext

Implements a Liferay HookDeployer that checks for conflicts in hooks that overload the same feature twice (if it can only be overloaded once).
Current features: 

* prohibits overloading the same JSP from two hooks
* prohibits overloading of the same Struts action from two hooks

To Do:

* Prohibit configuring the same portal.property from two hooks (identify those that conflict with each other) 

See https://www.liferay.com/web/olaf.kock/blog/-/blogs/overriding-features-from-different-hooks-chapter-2-struts-actions for more information

Currently Proof of Concept. Please test on other versions, give feedback and help improving it.
