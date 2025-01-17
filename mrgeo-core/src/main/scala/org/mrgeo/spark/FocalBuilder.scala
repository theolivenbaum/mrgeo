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

package org.mrgeo.spark

import java.awt.image.{Raster, WritableRaster}

import org.apache.spark.rdd.{PairRDDFunctions, RDD}
import org.apache.spark.{Logging, SparkContext}
import org.mrgeo.data.raster.{RasterUtils, RasterWritable}
import org.mrgeo.data.tile.TileIdWritable
import org.mrgeo.utils.tms.{Tile, Bounds, TMSUtils}

import scala.collection.mutable.ListBuffer

object FocalBuilder extends Logging {

  def create(tiles:RDD[(TileIdWritable, RasterWritable)],
      bufferX:Int, bufferY:Int, bounds:Bounds, zoom:Int, nodatas:Array[Number], context:SparkContext):RDD[(TileIdWritable, RasterWritable)] = {

    val sample:Raster = RasterWritable.toRaster(tiles.first()._2)

    val tilesize = sample.getWidth

    val offsetX = (bufferX / tilesize) + 1
    val offsetY = (bufferY / tilesize) + 1

    val dstW = sample.getWidth + bufferX * 2
    val dstH = sample.getHeight + bufferY * 2

    val tb = TMSUtils.boundsToTile(bounds, zoom, tilesize)
    val minX = tb.w
    val minY = tb.s
    val maxX = tb.e
    val maxY = tb.n

    val partitions = Math.min(context.getConf.getInt("spark.executor.cores", Int.MaxValue) * 2, tiles.partitions.length)

    logInfo("Using " + partitions + " partitions for grouping")

    val pieces = new PairRDDFunctions[TileIdWritable, (Int, Int, Int, Int, RasterWritable)](tiles.flatMap(tile => {
      val pieces = ListBuffer[(TileIdWritable, (Int, Int, Int, Int, RasterWritable))]()
      val from = TMSUtils.tileid(tile._1.get(), zoom)

      val src = RasterWritable.toRaster(tile._2)
      val srcW = src.getWidth
      val srcH = src.getHeight

      var y: Int = -offsetY
      while (y <= offsetY) {
        var x: Int = -offsetX
        while (x <= offsetX) {
          val to = new Tile(from.tx + x, from.ty + y)
          if (to.ty >= minY && to.ty <= maxY && to.tx >= minX && to.tx <= maxX) {
            var srcX = -1
            var dstX = -1

            var width = bufferX
            if (x == offsetX) {
              srcX = srcW - width
              dstX = 0
            }
            else if (x == -offsetX) {
              srcX = 0
              dstX = dstW - width
            }
            else {
              srcX = 0
              dstX = bufferX + (x * srcW)
              width = srcW
            }

            var srcY = -1
            var dstY = -1

            var height = bufferY
            if (y == -offsetY) {
              srcY = srcH - height
              dstY = 0
            }
            else if (y == offsetY) {
              srcY = 0
              dstY = dstH - height
            }
            else {
              srcY = 0
              dstY = bufferY + (y * srcH)
              height = srcH
            }

            val piece = src.createChild(srcX, srcY, width, height, 0, 0, null)
            pieces.append((new TileIdWritable(TMSUtils.tileid(to.tx, to.ty, zoom)), (dstX, dstY, width, height, RasterWritable.toWritable(piece))))
          }
          x += 1
        }
        y += 1
      }
      pieces.iterator

    })).groupByKey() // .groupByKey(partitions)

    val focal = pieces.map(tile => {
      val first = RasterWritable.toRaster(tile._2.head._5)
      val dst: WritableRaster = RasterUtils.createCompatibleEmptyRaster(first, dstW, dstH, nodatas)

      for (piece <- tile._2) {
        val x = piece._1
        val y = piece._2
        val w = piece._3
        val h = piece._4
        val src = RasterWritable.toRaster(piece._5)

        dst.setDataElements(x, y, w, h, src.getDataElements(0, 0, src.getWidth, src.getHeight, null))
      }

      (new TileIdWritable(tile._1), RasterWritable.toWritable(dst))
    })

    focal
  }

}
