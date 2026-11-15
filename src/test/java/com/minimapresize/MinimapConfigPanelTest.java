package com.minimapresize;

import com.google.gson.Gson;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.client.account.SessionManager;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigClient;
import net.runelite.client.config.ConfigDescriptor;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.ProfileManager;
import net.runelite.client.eventbus.EventBus;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Real config proxy/default storage and RuneLite's Swing settings builder, without a client or disk profile. */
public class MinimapConfigPanelTest
{
	@Rule public TemporaryFolder temporary = new TemporaryFolder();
	private ConfigManager manager;

	@Before
	public void setup() throws Exception
	{
		Constructor<ConfigManager> constructor = ConfigManager.class.getDeclaredConstructor(String.class,
			ScheduledExecutorService.class, EventBus.class, Client.class, Gson.class, ConfigClient.class,
			ProfileManager.class, SessionManager.class);
		constructor.setAccessible(true);
		manager = constructor.newInstance(null, mock(ScheduledExecutorService.class),
			new EventBus(error -> { throw new AssertionError(error); }), mock(Client.class), new Gson(),
			mock(ConfigClient.class), mock(ProfileManager.class), mock(SessionManager.class));
		Class<?> dataClass = Class.forName("net.runelite.client.config.ConfigData");
		Constructor<?> dataConstructor = dataClass.getDeclaredConstructor(File.class);
		dataConstructor.setAccessible(true);
		Field profile = ConfigManager.class.getDeclaredField("configProfile");
		profile.setAccessible(true);
		profile.set(manager, dataConstructor.newInstance(temporary.newFile("settings.properties")));
	}

	@Test
	public void freshConfigCanOpenAndEditSettingsBeforePluginStartup() throws Exception
	{
		MinimapResizeConfig config = new MinimapResizePlugin().provideConfig(manager);
		openPanelAndChangeMinimapFilter(config);
	}

	@Test
	public void upgradingExistingXbrConfigFillsNewDropdownWithoutResettingPreferences() throws Exception
	{
		manager.setConfiguration(MinimapResizeConfig.GROUP, "filter", "XBR");
		manager.setConfiguration(MinimapResizeConfig.GROUP, "smooth", "true");
		manager.setConfiguration(MinimapResizeConfig.GROUP, "scale", "200");
		manager.setConfiguration(MinimapResizeConfig.GROUP, "offsetX", "47");
		MinimapResizeConfig config = new MinimapResizePlugin().provideConfig(manager);
		assertEquals(ScalingFilter.XBR, config.filter());
		assertEquals(200, config.scale());
		assertEquals(47, config.offsetX());
		assertEquals("BILINEAR", manager.getConfiguration(MinimapResizeConfig.GROUP, "minimapFilter"));
		openPanelAndChangeMinimapFilter(config);
	}

	@Test
	public void interactionCompatibilityIsOptInAndExplicitPreferenceSurvivesProvision()
	{
		MinimapResizePlugin plugin = new MinimapResizePlugin();
		assertFalse(plugin.provideConfig(manager).preservePanelActions());
		manager.setConfiguration(MinimapResizeConfig.GROUP, "preservePanelActions", "true");
		assertTrue(plugin.provideConfig(manager).preservePanelActions());
		manager.setConfiguration(MinimapResizeConfig.GROUP, "preservePanelActions", "false");
		assertFalse(plugin.provideConfig(manager).preservePanelActions());
	}

	@Test
	public void legacyOverheadSizeMigratesToIndependentControlsWithoutResettingOverrides()
	{
		manager.setConfiguration(MinimapResizeConfig.GROUP, "overheadScale", "175");
		manager.setConfiguration(MinimapResizeConfig.GROUP, "prayerIconScale", "250");
		MinimapResizeConfig config = new MinimapResizePlugin().provideConfig(manager);
		assertEquals(175, config.healthBarScale());
		assertEquals(175, config.hitsplatScale());
		assertEquals(250, config.prayerIconScale());
		assertEquals(175, config.skullIconScale());
		assertEquals(175, config.overheadTextScale());
		manager.setConfiguration(MinimapResizeConfig.GROUP, "healthBarScale", "125");
		assertEquals(125, config.healthBarScale());
		assertEquals(175, config.hitsplatScale());
	}

	@Test
	public void repeatedProvisionPreservesExplicitMinimapFilter() throws Exception
	{
		manager.setConfiguration(MinimapResizeConfig.GROUP, "filter", "XBR");
		manager.setConfiguration(MinimapResizeConfig.GROUP, "minimapFilter", "LANCZOS");
		MinimapResizePlugin plugin = new MinimapResizePlugin();
		plugin.provideConfig(manager);
		MinimapResizeConfig config = plugin.provideConfig(manager);
		assertEquals(ScalingFilter.LANCZOS, config.minimapFilter());
		assertEquals(ScalingFilter.XBR, config.filter());
		openPanelAndChangeMinimapFilter(config);
	}

	private void openPanelAndChangeMinimapFilter(MinimapResizeConfig config) throws Exception
	{
		// ConfigPanel and its descriptor are package-private. Reflection stays in
		// tests so we exercise the actual dropdown builder that failed in-game.
		Class<?> panelClass = Class.forName("net.runelite.client.plugins.config.ConfigPanel");
		Constructor<?> panelConstructor = panelClass.getDeclaredConstructors()[0];
		panelConstructor.setAccessible(true);
		Object[] dependencies = Arrays.stream(panelConstructor.getParameterTypes())
			.map(type -> type == ConfigManager.class ? manager : mock(type)).toArray();
		Class<?> descriptorClass = Class.forName("net.runelite.client.plugins.config.PluginConfigurationDescriptor");
		Constructor<?> descriptorConstructor = descriptorClass.getDeclaredConstructor(String.class, String.class,
			String[].class, Config.class, ConfigDescriptor.class);
		descriptorConstructor.setAccessible(true);
		Object descriptor = descriptorConstructor.newInstance("Inventory & Minimap Resize", "Settings regression",
			new String[0], config, manager.getConfigDescriptor(config));
		Method init = panelClass.getDeclaredMethod("init", descriptorClass);
		init.setAccessible(true);
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				Container panel = (Container) panelConstructor.newInstance(dependencies);
				init.invoke(panel, descriptor);
				JComboBox<?> dropdown = findFirstDropdown(panel);
				assertNotNull("Minimap filter dropdown must be present", dropdown);
				assertEquals(config.minimapFilter(), dropdown.getSelectedItem());
				dropdown.setSelectedItem(ScalingFilter.HYBRID);
				assertEquals("HYBRID", manager.getConfiguration(MinimapResizeConfig.GROUP, "minimapFilter"));
				assertEquals(ScalingFilter.HYBRID, config.minimapFilter());
			}
			catch (ReflectiveOperationException error) { throw new AssertionError(error); }
		});
	}

	private static JComboBox<?> findFirstDropdown(Container container)
	{
		for (Component component : container.getComponents())
		{
			if (component instanceof JComboBox) { return (JComboBox<?>) component; }
			if (component instanceof Container)
			{
				JComboBox<?> found = findFirstDropdown((Container) component);
				if (found != null) { return found; }
			}
		}
		return null;
	}
}
