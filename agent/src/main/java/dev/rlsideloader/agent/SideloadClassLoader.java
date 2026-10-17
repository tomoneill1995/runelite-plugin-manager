package dev.rlsideloader.agent;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import net.runelite.client.RuneLite;
import net.runelite.client.util.ReflectUtil;

/**
 * One class loader per sideloaded jar, mirroring RuneLite's PluginHubClassLoader.
 * Implementing PrivateLookupableClassLoader lets the event bus build @Subscribe handlers.
 * <p>
 * Classes not found in the jar (or RuneLite) are looked up in the loaders of the jars it
 * depends on, so a plugin can use a shared API plugin that was sideloaded separately.
 */
final class SideloadClassLoader extends URLClassLoader implements ReflectUtil.PrivateLookupableClassLoader
{
	private final List<SideloadClassLoader> dependencies;
	private MethodHandles.Lookup lookup;

	SideloadClassLoader(String id, URL jar, List<SideloadClassLoader> dependencies)
	{
		super("rl-sideloader:" + id, new URL[]{jar}, RuneLite.class.getClassLoader());
		this.dependencies = new ArrayList<>(dependencies);
		ReflectUtil.installLookupHelper(this);
	}

	@Override
	protected Class<?> findClass(String name) throws ClassNotFoundException
	{
		try
		{
			return super.findClass(name);
		}
		catch (ClassNotFoundException e)
		{
			for (SideloadClassLoader dep : dependencies)
			{
				try
				{
					return dep.loadClass(name);
				}
				catch (ClassNotFoundException ignored)
				{
				}
			}
			throw e;
		}
	}

	@Override
	public URL findResource(String name)
	{
		URL url = super.findResource(name);
		for (int i = 0; url == null && i < dependencies.size(); i++)
		{
			url = dependencies.get(i).getResource(name);
		}
		return url;
	}

	@Override
	public Enumeration<URL> findResources(String name) throws IOException
	{
		List<URL> urls = Collections.list(super.findResources(name));
		for (SideloadClassLoader dep : dependencies)
		{
			urls.addAll(Collections.list(dep.getResources(name)));
		}
		return Collections.enumeration(urls);
	}

	/** Classes defined by this jar itself, as opposed to borrowed from a dependency */
	boolean defines(Class<?> clazz)
	{
		return clazz.getClassLoader() == this;
	}

	@Override
	public Class<?> defineClass0(String name, byte[] b, int off, int len) throws ClassFormatError
	{
		return super.defineClass(name, b, off, len);
	}

	@Override
	public MethodHandles.Lookup getLookup()
	{
		return lookup;
	}

	@Override
	public void setLookup(MethodHandles.Lookup lookup)
	{
		this.lookup = lookup;
	}
}
