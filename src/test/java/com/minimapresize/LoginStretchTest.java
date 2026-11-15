package com.minimapresize;

import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.event.MouseEvent;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.stretchedmode.StretchedModeConfig;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class LoginStretchTest
{
	@Test
	public void loginAndWelcomeStretchUntilEnteringTheGameWithMatchingClicks()
	{
		Client client = mock(Client.class);
		MouseManager manager = mock(MouseManager.class);
		StretchedModeConfig config = mock(StretchedModeConfig.class);
		when(config.scalingFactor()).thenReturn(50);
		LoginStretch stretch = new LoginStretch(client, manager, config);
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		when(client.getCanvasWidth()).thenReturn(765);
		when(client.getCanvasHeight()).thenReturn(503);
		doAnswer(call -> { when(client.isStretchedEnabled()).thenReturn(call.getArgument(0)); return null; })
			.when(client).setStretchedEnabled(anyBoolean());
		when(client.getStretchedDimensions()).thenReturn(new Dimension(1530, 1006));
		stretch.update(false);
		verify(client).setStretchedEnabled(true);
		verify(client).setStretchedKeepAspectRatio(false);
		verify(client).setStretchedIntegerScaling(false);
		org.mockito.InOrder order = inOrder(client);
		order.verify(client).setScalingFactor(50);
		order.verify(client).setStretchedEnabled(true);
		MouseEvent click = new MouseEvent(new Canvas(), MouseEvent.MOUSE_PRESSED, 1, 0, 800, 600, 1, false, MouseEvent.BUTTON1);
		assertEquals(400, stretch.mousePressed(click).getX());
		assertEquals(300, stretch.mousePressed(click).getY());
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTopLevelInterfaceId()).thenReturn(InterfaceID.TOPLEVEL_DISPLAY);
		stretch.update(false);
		verify(client, never()).setStretchedEnabled(false);
		when(client.getTopLevelInterfaceId()).thenReturn(InterfaceID.TOPLEVEL_PRE_EOC);
		stretch.update(false);
		verify(client).setStretchedEnabled(false);
		assertSame(click, stretch.mousePressed(click));
		stretch.close();
		verify(manager).unregisterMouseListener(stretch);
		verify(manager).unregisterMouseWheelListener(stretch);
	}

	@Test
	public void unscaledFrameDoesNotTranslateClicksEvenWhenFutureDimensionsDiffer()
	{
		Client client = mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		LoginStretch stretch = new LoginStretch(client, mock(MouseManager.class), mock(StretchedModeConfig.class));
		stretch.update(false);
		when(client.isStretchedEnabled()).thenReturn(true);
		when(client.getCanvasWidth()).thenReturn(1530);
		when(client.getCanvasHeight()).thenReturn(1006);
		when(client.getStretchedDimensions()).thenReturn(new Dimension(1530, 1006));
		when(client.getRealDimensions()).thenReturn(new Dimension(765, 503));
		MouseEvent click = new MouseEvent(new Canvas(), MouseEvent.MOUSE_PRESSED, 1, 0, 800, 600, 1, false, MouseEvent.BUTTON1);
		assertSame("No visible stretch means no input stretch", click, stretch.mousePressed(click));
		when(client.getCanvasWidth()).thenReturn(1020);
		when(client.getCanvasHeight()).thenReturn(670);
		MouseEvent mapped = stretch.mousePressed(click);
		assertEquals(533, mapped.getX());
		assertEquals(399, mapped.getY());
		verify(client, never()).getRealDimensions();
		when(client.isStretchedEnabled()).thenReturn(false);
		assertSame("A disabled renderer must never receive stretched input", click, stretch.mousePressed(click));
		stretch.close();
	}

	@Test
	public void builtInStretchedModeTakesOverWithoutDoubleTranslatingOrBeingDisabled()
	{
		Client client = mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		LoginStretch stretch = new LoginStretch(client, mock(MouseManager.class), mock(StretchedModeConfig.class));
		stretch.update(false);
		stretch.update(true);
		MouseEvent move = new MouseEvent(new Canvas(), MouseEvent.MOUSE_MOVED, 1, 0, 800, 600, 0, false);
		assertSame(move, stretch.mouseMoved(move));
		stretch.close();
		verify(client, never()).setStretchedEnabled(false);
	}

	@Test
	public void alreadyEnabledStretchingIsLeftToItsExistingOwner()
	{
		Client client = mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		when(client.isStretchedEnabled()).thenReturn(true);
		LoginStretch stretch = new LoginStretch(client, mock(MouseManager.class), mock(StretchedModeConfig.class));
		stretch.update(false);
		stretch.close();
		verify(client, never()).setStretchedEnabled(anyBoolean());
	}
}
