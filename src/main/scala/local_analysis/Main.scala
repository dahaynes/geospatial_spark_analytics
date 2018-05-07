package local_analysis

import geotrellis.raster._
import geotrellis.spark._
import scala.io.StdIn.{readLine,readInt}
import geotrellis.raster.io.geotiff.reader.GeoTiffReader
//Has TileLayout Object, MultibandTile
import geotrellis.raster.io.geotiff._
import scala.io.Source
import geotrellis.vector._
import geotrellis.vector.io._

import geotrellis.raster.render._
import geotrellis.raster.resample._
import geotrellis.raster.reproject._

import geotrellis.raster.summary.polygonal._
import geotrellis.raster.rasterize._
import geotrellis.raster.rasterize.polygon._
import geotrellis.raster.mapalgebra.local._
// import geotrellis.proj4._

import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.file._
import geotrellis.spark.io.hadoop._
import geotrellis.spark.io.index._
import geotrellis.spark.pyramid._
import geotrellis.spark.reproject._
import geotrellis.spark.tiling._
import geotrellis.spark.render._

//Vector Json
import geotrellis.vector._
import geotrellis.vector.io._
import geotrellis.vector.io.json._

//ProjectedExtent object
import org.apache.spark._
import org.apache.spark.rdd._

import org.apache.spark.HashPartitioner
import org.apache.spark.rdd.RDD

//hadoop config libraries
import org.apache.hadoop.fs.Path
import org.apache.hadoop.conf.Configuration

//Libraries for reading a json
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.io.StdIn
import java.io.File
import java.io._
//File Object

object RDDCount {
  //Class for testing raster analytics

  def countPixels(a:Int, b:geotrellis.raster.Tile) : Int = {
    var pixelCount:Int = 0
    b.foreach {z => if(z==a) pixelCount += 1}
    pixelCount
  }

  def countPixelsSpark(a:Int, b:org.apache.spark.rdd.RDD[(geotrellis.spark.SpatialKey, geotrellis.raster.Tile)]) : Int = {
    //The code below could potentially be simplified by using mapValues on the pair RDD vs map on the normal RDD.
    val RDDValues: org.apache.spark.rdd.RDD[geotrellis.raster.Tile] = b.values
    val y = RDDValues.map(x => countPixels(a,x))
    val sumOfPixels = y.collect.sum
    sumOfPixels
  }

  def countRaster(theRaster:org.apache.spark.rdd.RDD[(geotrellis.spark.SpatialKey, geotrellis.raster.Tile)], oldValue:Int) : Double = {
    
    var countPixelStart = System.currentTimeMillis()
    var thePixels = countPixelsSpark(oldValue, theRaster)
    var countPixelStop = System.currentTimeMillis()
    val theTime = countPixelStop - countPixelStart
    println(s"Milliseconds: ${countPixelStop - countPixelStart}")
    thePixels
    }  

  def reclassifyRaster(theRaster:org.apache.spark.rdd.RDD[(geotrellis.spark.SpatialKey, geotrellis.raster.Tile)], oldValue:Int, newValue:Int) : Double = {
    //Function for reclassifying raster
    
    //Type for evaluation statement
    type MyType = Int => Boolean
    var equalpixelvalue: MyType = (x: Int) => x == oldValue

    var reclassPixelStart = System.currentTimeMillis()
    var reclassedRaster = theRaster.localIf(equalpixelvalue, newValue, -999)
    var numReclassPixels = countPixelsSpark(newValue, reclassedRaster)
    var reclassPixelStop = System.currentTimeMillis()
    reclassedRaster.unpersist()
    val reclassTime = reclassPixelStop - reclassPixelStart
    reclassTime
  }
//"file://" + new File("data/r-g-nir.tif").getAbsolutePath
  val inputPath = "file://" + new File("/home/david/Downloads/glc2000.tif").getAbsolutePath

  def main(args: Array[String]): Unit = {
    //Call Reclass Pixel Function (add one to specified value)
    //reclassPixels(pixel,rasterArray);


    val conf = new SparkConf().setMaster("local[2]").setAppName("Spark Tiler").set("spark.serializer", "org.apache.spark.serializer.KryoSerializer").set("spark.kryo.regisintrator", "geotrellis.spark.io.kryo.KryoRegistrator")//.set("spark.driver.memory", "2g").set("spark.executor.memory", "1g")
    val sc = new SparkContext(conf)

    try {
      run(sc)
      // Pause to wait to close the spark context,
      // so that you can check out the UI at http://localhost:4040
      println("Hit enter to exit.")
      StdIn.readLine()
    } finally {
      sc.stop()
    }
  }


    def run(implicit sc: SparkContext) = {
      // Read the geotiff in as a single image RDD,
      // using a method implicitly added to SparkContext by
      // an implicit class available via the
      // "import geotrellis.spark.io.hadoop._ " statement.
      // val inputRdd: RDD[(ProjectedExtent, Tile)] = sc.hadoopGeoTiffRDD(inputPath)

      class rasterDataset(val name: String, val thePath: String, var pixelValue: Int, var newPixel: Int)
      val tilesizes = Array(25, 50, 100) //, 200, 300, 400, 500, 600, 700, 800, 900, 1000) //, 1500, 2000, 2500, 3000, 3500, 4000)

      val rasterDatasets = List(
        new rasterDataset("glc", "/home/david/Downloads/glc2000_clipped.tif", 16, 1),
        new rasterDataset("meris", "/home/david/Downloads/meris_2010.tif", 100, 1)
        //new rasterDataset("nlcd", "/data/projects/G-818404/nlcd_2006.tif", 21, 1),
        //new rasterDataset("meris_3m", "/data/projects/G-818404/meris_2010_clipped_3m.tif", 100, 1)
      )

      val outCSVPath = "/home/david/Downloads/test.csv" //"/home/04489/dhaynes/geotrellis_all_4_12_2018_12instances.csv"
      val writer = new PrintWriter("file://" + new File(outCSVPath).getAbsolutePath)
      writer.write("analytic,dataset,tilesize,time,run\n")

      for(r <- rasterDatasets) {

        val inputPath = "file://" + new File(r.thePath).getAbsolutePath
        val rasterRDD: RDD[(ProjectedExtent, geotrellis.raster.Tile)] = sc.hadoopGeoTiffRDD(inputPath)
        val geoTiff: SinglebandGeoTiff = SinglebandGeoTiff(r.thePath, decompress = false, streaming = true)
        val rasterArray: geotrellis.raster.Tile = geoTiff.tile

        for (tilesize <- tilesizes) {
          val ld = LayoutDefinition(geoTiff.rasterExtent, tilesize)
          val tiledRaster: RDD[(SpatialKey, geotrellis.raster.Tile)] = rasterRDD.tileToLayout(geoTiff.cellType, ld)
          var datasetName: String = r.name

          //Call Spark Function to count pixels
          val numPixels = countRaster(tiledRaster, r.pixelValue)

          writer.write(s"pixlecount,$datasetName,$tilesize,$numPixels,analyticTime,x\n")
        }
      }
      writer.close()

    }
  //val geoTiffRDD = HadoopGeoTiffRDD.spatial(new Path(localGeoTiffPath))

}
