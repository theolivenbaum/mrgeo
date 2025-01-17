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

package org.mrgeo.buildpyramid

import java.awt.image.{Raster, WritableRaster}
import java.io.{Externalizable, IOException, ObjectInput, ObjectOutput}
import java.util
import java.util.Properties

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.apache.commons.lang3.NotImplementedException
import org.apache.hadoop.conf.Configuration
import org.apache.spark.rdd.{PairRDDFunctions, RDD}
import org.apache.spark.{SparkConf, SparkContext}
import org.mrgeo.aggregators.{Aggregator, AggregatorRegistry, MeanAggregator}
import org.mrgeo.data
import org.mrgeo.data.DataProviderFactory.AccessMode
import org.mrgeo.data.image.{ImageOutputFormatContext, MrsImageDataProvider, MrsImageReader, MrsImageWriter}
import org.mrgeo.data.raster.{RasterUtils, RasterWritable}
import org.mrgeo.data.rdd.RasterRDD
import org.mrgeo.data.tile.TileIdWritable
import org.mrgeo.data.{CloseableKVIterator, DataProviderFactory, KVIterator, ProviderProperties}
import org.mrgeo.image.{ImageStats, MrsPyramid, MrsPyramidMetadata}
import org.mrgeo.job.{JobArguments, MrGeoDriver, MrGeoJob}
import org.mrgeo.utils._
import org.mrgeo.utils.tms._

import scala.beans.BeanProperty
import scala.collection.JavaConversions._
import scala.collection.mutable

object BuildPyramid extends MrGeoDriver with Externalizable {

  private val Pyramid = "pyramid"
  private val Aggregator = "aggregator"
  private val ProviderProperties = "provider.properties"

  @BeanProperty
  var MIN_TILES_FOR_SPARK = 1000  // made a var so the tests can muck with it...

  def build(pyramidName: String, aggregator: Aggregator, conf: Configuration,
      providerProperties: ProviderProperties):Boolean = {

    val name = "BuildPyramid"

    val args = setupArguments(pyramidName, aggregator, providerProperties)

    run(name, classOf[BuildPyramid].getName, args.toMap, conf)

    true
  }

  def buildlevel(pyramidName: String, level: Int, aggregator: Aggregator,
      conf: Configuration, providerProperties: Properties):Boolean = {
    throw new NotImplementedException("Not yet implemented")
  }

  // this build method allows buildpyramid to be called from within an existing spark job...
  def build(pyramidName: String, aggregator: Aggregator, context:SparkContext,
      providerProperties: ProviderProperties):Boolean = {
    val bp = new BuildPyramid(pyramidName, aggregator, providerProperties)

    bp.execute(context)
  }

  private def setupArguments(pyramid: String, aggregator: Aggregator, providerProperties: ProviderProperties):mutable.Map[String, String] = {
    val args = mutable.Map[String, String]()

    args += Pyramid -> pyramid
    args += Aggregator -> aggregator.getClass.getName

    if (providerProperties != null)
    {
      args += ProviderProperties -> data.ProviderProperties.toDelimitedString(providerProperties)
        }
    else
    {
      args += ProviderProperties -> ""
    }

    args
  }


  override def setup(job: JobArguments): Boolean = {
    job.isMemoryIntensive = true
    true
  }

  override def readExternal(in: ObjectInput): Unit = {}

  override def writeExternal(out: ObjectOutput): Unit = {}
}

class BuildPyramid extends MrGeoJob with Externalizable {

  var pyramidName:String = null
  var aggregator:Aggregator = null
  var providerproperties:ProviderProperties = null

  private[buildpyramid] def this(pyramidName: String, aggregator: Aggregator,
    providerProperties: ProviderProperties) = {
    this()

    this.pyramidName = pyramidName
    this.aggregator = aggregator
    this.providerproperties = providerproperties
  }

  override def registerClasses(): Array[Class[_]] = {
    val classes = Array.newBuilder[Class[_]]

    classes.result()
  }

  private def makeAggregator(classname: String) = {
    val cl = getClass.getClassLoader
    val clazz = cl.loadClass(classname)

    aggregator = clazz.newInstance().asInstanceOf[Aggregator]
  }

  override def setup(job: JobArguments, conf: SparkConf): Boolean = {
    pyramidName = job.getSetting(BuildPyramid.Pyramid)
    val aggclass = job.getSetting(BuildPyramid.Aggregator, classOf[MeanAggregator].getName)

    makeAggregator(aggclass)

    providerproperties = ProviderProperties.fromDelimitedString(
      job.getSetting(BuildPyramid.ProviderProperties))

    true
  }

  override def execute(context: SparkContext): Boolean = {

    implicit val tileIdOrdering = new Ordering[TileIdWritable] {
      override def compare(x: TileIdWritable, y: TileIdWritable): Int = x.compareTo(y)
    }

    log.warn("Building pyramid for " + pyramidName)
    val provider: MrsImageDataProvider =
      DataProviderFactory.getMrsImageDataProvider(pyramidName, AccessMode.READ, null.asInstanceOf[ProviderProperties])

    var metadata: MrsPyramidMetadata = provider.getMetadataReader.read

    val maxLevel: Int = metadata.getMaxZoomLevel

    val tilesize: Int = metadata.getTilesize

    val nodatas = metadata.getDefaultValuesNumber

    DataProviderFactory.saveProviderPropertiesToConfig(providerproperties, context.hadoopConfiguration)
    // build the levels
    for (level <- maxLevel until 1 by -1) {
      val fromlevel = level
      val tolevel = fromlevel - 1

      logInfo("Building pyramid for: " + pyramidName + " from: " + fromlevel + " to: " + tolevel)
      val tb = metadata.getTileBounds(fromlevel)

      // if we have less than 1000 tiles total, we'll use the local buildpyramid
      if (tb.getWidth * tb.getHeight > BuildPyramid.MIN_TILES_FOR_SPARK) {
        val pyramid = SparkUtils.loadMrsPyramid(provider, fromlevel, context)

        val decimated: RDD[(TileIdWritable, RasterWritable)] = pyramid.map(tile => {
          val fromkey = tile._1
          val fromraster = RasterWritable.toRaster(tile._2)

          val fromtile: Tile = TMSUtils.tileid(fromkey.get, fromlevel)
          val frombounds: Bounds = TMSUtils.tileBounds(fromtile.tx, fromtile.ty, fromlevel, tilesize)

          // calculate the starting pixel for the from-tile (make sure to use the NW coordinate)
          val fromcorner: Pixel = TMSUtils.latLonToPixelsUL(frombounds.n, frombounds.w, fromlevel, tilesize)

          val totile: Tile = TMSUtils.latLonToTile(frombounds.s, frombounds.w, tolevel, tilesize)
          val tobounds: Bounds = TMSUtils.tileBounds(totile.tx, totile.ty, tolevel, tilesize)

          // calculate the starting pixel for the to-tile (make sure to use the NW coordinate) in the from-tile's pixel space
          val tocorner: Pixel = TMSUtils.latLonToPixelsUL(tobounds.n, tobounds.w, fromlevel, tilesize)

          val tokey = new TileIdWritable(TMSUtils.tileid(totile.tx, totile.ty, tolevel))

          // create a compatible writable raster
          val toraster: WritableRaster =
            RasterUtils.createCompatibleEmptyRaster(fromraster, tilesize, tilesize, nodatas)

          logDebug("from  tx: " + fromtile.tx + " ty: " + fromtile.ty + " (" + fromlevel + ") to tx: " + totile.tx +
              " ty: " + totile.ty + " (" + tolevel + ") x: "
              + ((fromcorner.px - tocorner.px) / 2) + " y: " + ((fromcorner.py - tocorner.py) / 2) +
              " w: " + fromraster.getWidth + " h: " + fromraster.getHeight)

          RasterUtils.decimate(fromraster, toraster,
            (fromcorner.px - tocorner.px).toInt / 2, (fromcorner.py - tocorner.py).toInt / 2,
            aggregator, nodatas)

          (tokey, RasterWritable.toWritable(toraster))
        })


        //val tileBounds = TMSUtils.boundsToTile(metadata.getBounds, tolevel, tilesize)

        val wrappedDecimated = new PairRDDFunctions(decimated)
        val mergedTiles = wrappedDecimated.reduceByKey((r1, r2) => {
          val src = RasterWritable.toRaster(r1)
          val dst = RasterUtils.makeRasterWritable(RasterWritable.toRaster(r2))

          RasterUtils.mosaicTile(src, dst, metadata.getDefaultValues)

          RasterWritable.toWritable(dst)
        })

        // while we were running, there is chance the pyramid was removed from the cache and
        // reopened by another process. Re-opening it here will avoid some potential conflicts.
        metadata = MrsPyramid.open(provider).getMetadata

        // make sure the level is deleted
        deletelevel(tolevel, metadata, provider)

        SparkUtils.saveMrsPyramid(RasterRDD(mergedTiles), provider, tolevel,
          context.hadoopConfiguration, providerproperties = this.providerproperties)

        //TODO: Fix this in S3
        // in S3, sometimes the just-written data isn't available to read yet.  This sleep just gives
        // S3 a chance to catch up...
        Thread.sleep(5000)
      }
      else {
        buildlevellocal(provider, fromlevel)
      }
    }

    true
  }

  private def deletelevel(level: Int, metadata: MrsPyramidMetadata, provider: MrsImageDataProvider) {
    val imagedata: Array[MrsPyramidMetadata.ImageMetadata] = metadata.getImageMetadata

    // delete the level
    provider.delete(level)

    // remove the metadata for the level
    imagedata(level) = new MrsPyramidMetadata.ImageMetadata
    provider.getMetadataWriter.write()
  }

  // this method was stolen from the old Hadoop M/R version of BuildPyramid.  I really haven't looked much
  // into it to see if it really is still OK or could be improved
  @SuppressFBWarnings(value = Array("RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT"), justification = "tileIdOrdering() - false positivie")
  private def buildlevellocal(provider:MrsImageDataProvider, inputLevel: Int): Boolean = {

    implicit val tileIdOrdering = new Ordering[TileIdWritable] {
      override def compare(x: TileIdWritable, y: TileIdWritable): Int = x.compareTo(y)
    }

    var metadata: MrsPyramidMetadata = provider.getMetadataReader.read

    val bounds: Bounds = metadata.getBounds
    val tilesize: Int = metadata.getTilesize
    val outputLevel: Int = inputLevel - 1

    deletelevel(outputLevel, metadata, provider)

    val outputTiles = new util.TreeMap[TileIdWritable, WritableRaster]()

    val lastImage: MrsImageReader = provider.getMrsTileReader(inputLevel)
    val iter: KVIterator[TileIdWritable, Raster] = lastImage.get
    while (iter.hasNext) {
      val fromraster: Raster = iter.next

      val tileid: Long = iter.currentKey.get
      val inputTile: Tile = TMSUtils.tileid(tileid, inputLevel)

      val toraster: WritableRaster = fromraster.createCompatibleWritableRaster(tilesize / 2, tilesize / 2)

      RasterUtils.decimate(fromraster, toraster, aggregator, metadata)

      val outputTile: Tile = TMSUtils.calculateTile(inputTile, inputLevel, outputLevel, tilesize)
      val outputkey: TileIdWritable = new TileIdWritable(TMSUtils.tileid(outputTile.tx, outputTile.ty, outputLevel))
      var outputRaster: WritableRaster = null

      if (!outputTiles.contains(outputkey)) {
        outputRaster = fromraster.createCompatibleWritableRaster(tilesize, tilesize)
        RasterUtils.fillWithNodata(outputRaster, metadata)
        outputTiles.put(outputkey, outputRaster)
      }
      else {
        outputRaster = outputTiles(outputkey)
      }

      val outputBounds: Bounds = TMSUtils.tileBounds(outputTile.tx, outputTile.ty, outputLevel, tilesize)
      val corner: Pixel = TMSUtils.latLonToPixelsUL(outputBounds.n, outputBounds.w, outputLevel, tilesize)
      val inputBounds: Bounds = TMSUtils.tileBounds(inputTile.tx, inputTile.ty, inputLevel, tilesize)
      val start: Pixel = TMSUtils.latLonToPixelsUL(inputBounds.n, inputBounds.w, outputLevel, tilesize)
      val tox: Int = (start.px - corner.px).toInt
      val toy: Int = (start.py - corner.py).toInt
      logDebug(
        "Calculating tile from  tx: " + inputTile.tx + " ty: " + inputTile.ty + " (" + inputLevel + ") to tx: " +
            outputTile.tx + " ty: " + outputTile.ty + " (" + outputLevel + ") x: " + tox + " y: " + toy)
      outputRaster.setDataElements(tox, toy, toraster)
    }

    iter match {
    case value: CloseableKVIterator[_, _] =>
      try {
        value.close()
      }
      catch {
        case e: IOException =>
          e.printStackTrace()
      }
    case _ =>
    }


    val stats: Array[ImageStats] = ImageStats.initializeStatsArray(metadata.getBands)
    log.debug("Writing output file: " + provider.getResourceName + " level: " + outputLevel)
    val writer: MrsImageWriter = provider.getMrsTileWriter(outputLevel, metadata.getProtectionLevel)
    import scala.collection.JavaConversions._
    for (tile <- outputTiles.entrySet) {
      logDebug("  writing tile: " + tile.getKey.get)
      writer.append(tile.getKey, tile.getValue)
      ImageStats.computeAndUpdateStats(stats, tile.getValue, metadata.getDefaultValues)
    }
    writer.close()
    val tb: TileBounds = TMSUtils
        .boundsToTile(bounds, outputLevel, tilesize)
    val b: LongRectangle = new LongRectangle(tb.w, tb.s, tb.e, tb.n)
    val psw: Pixel = TMSUtils.latLonToPixels(bounds.s, bounds.w, outputLevel, tilesize)
    val pne: Pixel = TMSUtils.latLonToPixels(bounds.n, bounds.e, outputLevel, tilesize)


    // while we were running, there is chance the pyramid was removed from the cache and
    // reopened by another process. Re-opening it here will avoid some potential conflicts.
    metadata = MrsPyramid.open(provider).getMetadata

    metadata.setPixelBounds(outputLevel, new LongRectangle(0, 0, pne.px - psw.px, pne.py - psw.py))
    metadata.setTileBounds(outputLevel, b)
    metadata.setName(outputLevel)
    metadata.setImageStats(outputLevel, stats)
    metadata.setResamplingMethod(AggregatorRegistry.aggregatorRegistry.inverse.get(aggregator.getClass))

    lastImage.close()
    provider.getMetadataWriter(null).write()

    val tofc = new ImageOutputFormatContext(provider.getResourceName, bounds, outputLevel,
      tilesize, metadata.getProtectionLevel)
    val tofp = provider.getTiledOutputFormatProvider(tofc)
    // Don't use teardownForSpark because we built this level in locally, so we want
    // to avoid using Spark at this point
    tofp.finalizeExternalSave(HadoopUtils.createConfiguration())
    true
  }

  override def teardown(job: JobArguments, conf: SparkConf): Boolean = {
    true
  }

  override def readExternal(in: ObjectInput): Unit = {
    pyramidName = in.readUTF()
    val ac = in.readUTF()
    makeAggregator(ac)
  }

  override def writeExternal(out: ObjectOutput): Unit = {
    out.writeUTF(pyramidName)
    out.writeUTF(aggregator.getClass.getName)
  }
}
