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

package org.mrgeo.data;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.mrgeo.data.image.MrsImageDataProvider;
import org.mrgeo.data.raster.RasterWritable;
import org.mrgeo.data.image.ImageInputFormatContext;
import org.mrgeo.data.tile.TileIdWritable;
import org.mrgeo.mapreduce.splitters.MrsPyramidInputSplit;
import org.mrgeo.utils.tms.Bounds;
import org.mrgeo.utils.tms.TMSUtils;
import org.mrgeo.utils.tms.Tile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.Raster;
import java.io.IOException;

public class MrsPyramidRecordReader extends RecordReader<TileIdWritable, RasterWritable>
{
  private static final Logger log = LoggerFactory.getLogger(MrsPyramidRecordReader.class);
  private RecordReader<TileIdWritable, RasterWritable> scannedInputReader;
  private ImageInputFormatContext ifContext;
  private TileIdWritable key;
  private RasterWritable value;
  private Bounds inputBounds = Bounds.WORLD; // bounds of the map/reduce (either the image bounds or cropped though map algebra)

  private int tilesize;
  private int zoomLevel;

  protected Raster toNonWritableTile(RasterWritable tileValue) throws IOException
  {
    return RasterWritable.toRaster(tileValue);
  }

  protected RecordReader<TileIdWritable, RasterWritable> getRecordReader(
          final String name, final Configuration conf) throws DataProviderNotFound
  {
    MrsImageDataProvider dp = DataProviderFactory.getMrsImageDataProvider(name,
                                                                          DataProviderFactory.AccessMode.READ, conf);
    return dp.getRecordReader();
  }

  protected RasterWritable toWritable(Raster val) throws IOException
  {
    return RasterWritable.toWritable(val);
  }

  protected RasterWritable copyWritable(RasterWritable val)
  {
    return new RasterWritable(val);
  }

  private RecordReader<TileIdWritable,RasterWritable> createRecordReader(
          final MrsPyramidInputSplit split, final TaskAttemptContext context)
          throws IOException
  {
    InputSplit initializeWithSplit;
    // The record reader needs the native split returned from
    // the data plugin.
    RecordReader<TileIdWritable,RasterWritable> recordReader = getRecordReader(split.getName(),
                                                                          context.getConfiguration());
    initializeWithSplit = split.getWrappedSplit();

    try
    {
      recordReader.initialize(initializeWithSplit, context);
    }
    catch(Throwable t)
    {
      throw new IOException(t);
    }
    return recordReader;
  }

  @Override
  public void close() throws IOException
  {
    if (scannedInputReader != null)
    {
      scannedInputReader.close();
    }
  }

  @Override
  public TileIdWritable getCurrentKey()
  {
    return key;
  }

  @Override
  public RasterWritable getCurrentValue()
  {
    return value;
  }

  @Override
  public float getProgress() throws IOException, InterruptedException
  {
    return scannedInputReader.getProgress();
  }

  @Override
  public void initialize(InputSplit split, TaskAttemptContext context) throws IOException,
          InterruptedException
  {
    if (split instanceof MrsPyramidInputSplit)
    {
      final MrsPyramidInputSplit fsplit = (MrsPyramidInputSplit) split;
      //final Configuration conf = context.getConfiguration();

      ifContext = ImageInputFormatContext.load(context.getConfiguration());
      if (ifContext.getBounds() != null)
      {
        inputBounds = ifContext.getBounds();
      }
      scannedInputReader = createRecordReader(fsplit, context);
      tilesize = ifContext.getTileSize();
      zoomLevel = ifContext.getZoomLevel();
    }
    else
    {
      throw new IOException("Got a split of type " + split.getClass().getCanonicalName() +
                            " but expected one of type " + MrsPyramidInputSplit.class.getCanonicalName());
    }
  }

  @Override
  public boolean nextKeyValue() throws IOException, InterruptedException
  {
    while (scannedInputReader.nextKeyValue())
    {
      final long id = scannedInputReader.getCurrentKey().get();
//      log.info("scannedInputReader returned key " + id);

      final Tile tile = TMSUtils.tileid(id, zoomLevel);
      final Bounds tb = TMSUtils.tileBounds(tile.tx, tile.ty, zoomLevel, tilesize);
      if (inputBounds.intersects(tb.w, tb.s, tb.e, tb.n))
      {
        // RasterWritable.toRaster(scannedInputReader.getCurrentValue())
        setNextKeyValue(id, scannedInputReader.getCurrentValue());
//        log.info("Returning at point 3 after " + (System.currentTimeMillis() - start));
        return true;
      }
    }
//    log.info("Returning at point 4 after " + (System.currentTimeMillis() - start));
    return false;
  }

  private void setNextKeyValue(final long tileid, final RasterWritable tileValue)
  {
//    long start = System.currentTimeMillis();
//    log.info("setNextKeyValue");
//    try
//    {
//      Tile t = TMSUtils.tileid(tileid, zoomLevel);
//      QuickExport.saveLocalGeotiff("/export/home/dave.johnson/splits", raster, t.tx, t.ty,
//          zoomLevel, tilesize, -9999);
//    }
//    catch (NoSuchAuthorityCodeException e)
//    {
//      e.printStackTrace();
//    }
//    catch (IOException e)
//    {
//      e.printStackTrace();
//    }
//    catch (FactoryException e)
//    {
//      e.printStackTrace();
//    }
    key = new TileIdWritable(tileid);
    // The copy operation is required below for Spark RDD creation to prevent all the
    // raster tiles in an RDD (for one split) looking like the last tile in the split.
    value = copyWritable(tileValue);
//    log.info("After setNextKeyValue took" + (System.currentTimeMillis() - start));
  }
}
