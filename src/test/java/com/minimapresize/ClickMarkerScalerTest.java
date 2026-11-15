package com.minimapresize;

import java.awt.image.BufferedImage;
import net.runelite.api.Client;
import net.runelite.api.SpritePixels;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ClickMarkerScalerTest
{
	@Test
	public void scalesBothColoursCentredAndCachesAllAnimationFrames()
	{
		Client client = client();
		SpritePixels[] frames = new SpritePixels[8];
		for (int i = 0; i < frames.length; i++) { frames[i] = frame(i < 4 ? 0xffff00 : 0xff0000); }
		when(client.getCrossSprites()).thenReturn(frames);
		SpritePixels[] originals = frames.clone();
		ClickMarkerScaler scaler = new ClickMarkerScaler();
		scaler.update(client, 200, ScalingFilter.NEAREST, 40);
		for (int i = 0; i < frames.length; i++)
		{
			assertNotSame(originals[i], frames[i]);
			assertEquals(32, frames[i].getWidth());
			assertEquals(i < 4 ? 0xffff00 : 0xff0000, frames[i].getPixels()[0]);
			verify(frames[i]).setOffsetX(-8);
			verify(frames[i]).setOffsetY(-8);
		}
		scaler.update(client, 200, ScalingFilter.NEAREST, 40);
		verify(client, times(8)).createSpritePixels(any(int[].class), eq(32), eq(32));
		scaler.update(client, 100, ScalingFilter.NEAREST, 40);
		assertArrayEquals(originals, frames);
	}

	@Test
	public void resizesFromOriginalAndPreservesAnotherPluginsReplacementOnRestore()
	{
		Client client = client();
		SpritePixels original = frame(0xffff00);
		SpritePixels[] frames = {original};
		when(client.getCrossSprites()).thenReturn(frames);
		ClickMarkerScaler scaler = new ClickMarkerScaler();
		scaler.update(client, 200, ScalingFilter.NEAREST, 40);
		scaler.update(client, 50, ScalingFilter.NEAREST, 40);
		assertEquals(8, frames[0].getWidth());
		verify(frames[0]).setOffsetX(4);
		SpritePixels other = frame(0xff0000);
		frames[0] = other;
		scaler.restore();
		assertSame(other, frames[0]);
		scaler.update(client, 200, ScalingFilter.NEAREST, 40);
		scaler.restore();
		assertSame(other, frames[0]);
	}

	private static Client client()
	{
		Client client = mock(Client.class);
		when(client.createSpritePixels(any(int[].class), anyInt(), anyInt())).thenAnswer(call ->
		{
			SpritePixels sprite = mock(SpritePixels.class);
			when(sprite.getPixels()).thenReturn(call.getArgument(0));
			when(sprite.getWidth()).thenReturn(call.getArgument(1));
			when(sprite.getHeight()).thenReturn(call.getArgument(2));
			return sprite;
		});
		return client;
	}

	private static SpritePixels frame(int colour)
	{
		SpritePixels sprite = mock(SpritePixels.class);
		when(sprite.getWidth()).thenReturn(16);
		when(sprite.getHeight()).thenReturn(16);
		when(sprite.getMaxWidth()).thenReturn(16);
		when(sprite.getMaxHeight()).thenReturn(16);
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 16; y++)
		{
			for (int x = 0; x < 16; x++) { image.setRGB(x, y, 0xff000000 | colour); }
		}
		when(sprite.toBufferedImage()).thenReturn(image);
		return sprite;
	}
}
