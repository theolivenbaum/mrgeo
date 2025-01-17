/*
 * Copyright 2009-2016 DigitalGlobe, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 *
 */

package org.mrgeo.services.mrspyramid.rendering;

import ar.com.hjg.pngj.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.Raster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Writes PNG images to an HTTP response
 */
public class PngImageResponseWriter extends ImageResponseWriterAbstract
{
  private static final Logger log = LoggerFactory.getLogger(PngImageResponseWriter.class);

  /*
   * (non-Javadoc)
   * 
   * @see org.mrgeo.services.wms.ImageResponseWriter#getMimeType()
   */
  @Override
  public String[] getMimeTypes()
  {
    return new String[] { "image/png" };
  }

  @Override
  public String getResponseMimeType()
  {
    return "image/png";
  }

  @Override
  public String[] getWmsFormats()
  {
    return new String[] { "png" };
  }

  @Override
  public void writeToStream(final Raster raster, final double[] defaults,
    final ByteArrayOutputStream byteStream) throws IOException
  {
    // final long start = System.currentTimeMillis();

    final boolean alpha = raster.getNumBands() == 4;

    final int bands = raster.getNumBands();

    final ImageInfo imi = new ImageInfo(raster.getWidth(), raster.getHeight(), 8, true);
    final ImageLineInt line = new ImageLineInt(imi);

    final PngWriter writer = new PngWriter(byteStream, imi);
    writer.setFilterType(FilterType.FILTER_NONE); // no filtering
    writer.setCompLevel(6);

    final int r = 0;
    final int g = 1;
    final int b = 2;
    final int a = alpha ? 3 : 0;

    int rnodata;
    int gnodata;
    int bnodata;

    byte red;
    byte green;
    byte blue;

    if (!alpha)
    {
      rnodata = defaults != null && defaults.length > 0 ? (int) defaults[0] : Integer.MAX_VALUE;
      gnodata = defaults != null && defaults.length > 1 ? (int) defaults[1] : Integer.MAX_VALUE;
      bnodata = defaults != null && defaults.length > 2 ? (int) defaults[2] : Integer.MAX_VALUE;
    }
    else
    {
      rnodata = 0;
      gnodata = 0;
      bnodata = 0;
    }

    byte[] pixels = (byte[]) raster.getDataElements(0, 0, raster.getWidth(), raster.getHeight(), null);

    for (int y = 0, j = 0; y < raster.getHeight(); y++)
    {
      for (int x = 0; x < raster.getWidth(); x++, j += bands)
      {

        int[] pixel = raster.getPixel(x, y, (int[])null);
        if (alpha)
        {
          ImageLineHelper.setPixelRGBA8(line, x, pixels[j + r], pixels[j + g], pixels[j + b], pixels[j + a]);
        }
        else
        {
          red = (byte)pixel[0];
          green = (byte)pixel[1];
          blue =  (byte)pixel[2];

          ImageLineHelper.setPixelRGBA8(line, x, red, green, blue, (red == rnodata &&
              green == gnodata && blue == bnodata) ? 0 : 255);
        }
      }
      writer.writeRow(line);
    }
    writer.end();

    byteStream.close();
  }

}
