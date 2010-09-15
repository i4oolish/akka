/**
 *  Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.persistence.voldemort

import se.scalablesolutions.akka.stm._
import se.scalablesolutions.akka.persistence.common._
import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.util.Helpers._
import se.scalablesolutions.akka.config.Config.config

import voldemort.client._
import java.lang.String
import voldemort.utils.ByteUtils
import voldemort.versioning.Versioned
import collection.JavaConversions
import java.nio.ByteBuffer
import collection.immutable.{IndexedSeq, SortedSet, TreeSet}
import collection.mutable.{Map, Set, HashSet, ArrayBuffer}
import java.util.{Map => JMap}



private[akka] object VoldemortStorageBackend extends
MapStorageBackend[Array[Byte], Array[Byte]] with
        VectorStorageBackend[Array[Byte]] with
        RefStorageBackend[Array[Byte]] with
        Logging {
  val bootstrapUrl: String = config.getString("akka.storage.voldemort.bootstrap.url", "tcp://localhost:6666")
  val refStore = config.getString("akka.storage.voldemort.store.ref", "Refs")
  val mapKeyStore = config.getString("akka.storage.voldemort.store.map.key", "MapKeys")
  val mapValueStore = config.getString("akka.storage.voldemort.store.map.value", "MapValues")
  val vectorSizeStore = config.getString("akka.storage.voldemort.store.vector.size", "VectorSizes")
  val vectorValueStore = config.getString("akka.storage.voldemort.store.vectore.value", "VectorValues")
  val storeClientFactory = {
    if (bootstrapUrl.startsWith("tcp")) {
      new SocketStoreClientFactory(new ClientConfig().setBootstrapUrls(bootstrapUrl))
    } else if (bootstrapUrl.startsWith("http")) {
      new HttpStoreClientFactory(new ClientConfig().setBootstrapUrls(bootstrapUrl))
    } else {
      throw new IllegalArgumentException("Unknown boostrapUrl syntax" + bootstrapUrl)
    }
  }
  var refClient: StoreClient[String, Array[Byte]] = storeClientFactory.getStoreClient(refStore)
  var mapKeyClient: StoreClient[String, SortedSet[Array[Byte]]] = storeClientFactory.getStoreClient(mapKeyStore)
  var mapValueClient: StoreClient[Array[Byte], Array[Byte]] = storeClientFactory.getStoreClient(mapValueStore)
  var vectorSizeClient: StoreClient[String, Array[Byte]] = storeClientFactory.getStoreClient(vectorSizeStore)
  var vectorValueClient: StoreClient[Array[Byte], Array[Byte]] = storeClientFactory.getStoreClient(vectorValueStore)
  val underscoreBytesUTF8 = "_".getBytes("UTF-8")
  implicit val byteOrder = new Ordering[Array[Byte]] {
    override def compare(x: Array[Byte], y: Array[Byte]) = ByteUtils.compare(x, y)
  }


  def getRefStorageFor(name: String): Option[Array[Byte]] = {
    val result: Array[Byte] = refClient.getValue(name)
    result match {
      case null => None
      case _ => Some(result)
    }
  }

  def insertRefStorageFor(name: String, element: Array[Byte]) = {
    refClient.put(name, element)
  }

  def getMapStorageRangeFor(name: String, start: Option[Array[Byte]], finish: Option[Array[Byte]], count: Int): List[(Array[Byte], Array[Byte])] = {
    val allkeys: SortedSet[Array[Byte]] = mapKeyClient.getValue(name, new TreeSet[Array[Byte]])
    val range = allkeys.rangeImpl(start, finish).take(count)
    getKeyValues(range)
  }

  def getMapStorageFor(name: String): List[(Array[Byte], Array[Byte])] = {
    val keys = mapKeyClient.getValue(name, new TreeSet[Array[Byte]]())
    getKeyValues(keys)
  }

  private def getKeyValues(keys: SortedSet[Array[Byte]]): List[(Array[Byte], Array[Byte])] = {
    val all: JMap[Array[Byte], Versioned[Array[Byte]]] = mapValueClient.getAll(JavaConversions.asIterable(keys))
    JavaConversions.asMap(all).foldLeft(new ArrayBuffer[(Array[Byte], Array[Byte])](all.size)) {
      (buf, keyVal) => {
        keyVal match {
          case (key, versioned) => {
            buf += key -> versioned.getValue
          }
        }
        buf
      }
    }.toList
  }

  def getMapStorageSizeFor(name: String): Int = {
    val keys = mapKeyClient.getValue(name, new TreeSet[Array[Byte]]())
    keys.size
  }

  def getMapStorageEntryFor(name: String, key: Array[Byte]): Option[Array[Byte]] = {
    val result: Array[Byte] = mapValueClient.getValue(getKey(name, key))
    result match {
      case null => None
      case _ => Some(result)
    }
  }

  def removeMapStorageFor(name: String, key: Array[Byte]) = {
    var keys = mapKeyClient.getValue(name, new TreeSet[Array[Byte]]())
    keys -= key
    mapKeyClient.put(name, keys)
    mapValueClient.delete(getKey(name, key))
  }


  def removeMapStorageFor(name: String) = {
    val keys = mapKeyClient.getValue(name, new TreeSet[Array[Byte]]())
    keys.foreach {
      key =>
        mapValueClient.delete(getKey(name, key))
    }
    mapKeyClient.delete(name)
  }

  def insertMapStorageEntryFor(name: String, key: Array[Byte], value: Array[Byte]) = {
    mapValueClient.put(getKey(name, key), value)
    var keys = mapKeyClient.getValue(name, new TreeSet[Array[Byte]]())
    keys += key
    mapKeyClient.put(name, keys)
  }

  def insertMapStorageEntriesFor(name: String, entries: List[(Array[Byte], Array[Byte])]) = {
    val newKeys = entries.map {
      case (key, value) => {
        mapValueClient.put(getKey(name, key), value)
        key
      }
    }
    var keys = mapKeyClient.getValue(name, new TreeSet[Array[Byte]]())
    keys ++= newKeys
    mapKeyClient.put(name, keys)
  }


  def getVectorStorageSizeFor(name: String): Int = {
    IntSerializer.fromBytes(vectorSizeClient.getValue(name, IntSerializer.toBytes(0)))
  }


  def getVectorStorageRangeFor(name: String, start: Option[Int], finish: Option[Int], count: Int): List[Array[Byte]] = {
    val size = getVectorStorageSizeFor(name)
    val st = start.getOrElse(0)
    val cnt =
    if (finish.isDefined) {
      val f = finish.get
      if (f >= st) (f - st) else count
    } else {
      count
    }
    val seq: IndexedSeq[Array[Byte]] = (st to st + cnt).map {
      index => getVectorValueKey(name, index)
    }

    val all: JMap[Array[Byte], Versioned[Array[Byte]]] = vectorValueClient.getAll(JavaConversions.asIterable(seq))

    val buf = new ArrayBuffer[Array[Byte]](seq.size)
    seq.foreach {
      key => {
        val index = getIndexFromVectorValueKey(name, key)
        var value: Array[Byte] = null
        if (all.containsKey(key)) {
          value = all.get(key).getValue
        } else {
          value = Array.empty[Byte]
        }
        buf.update(index, value)
      }
    }
    buf.toList
  }


  def getVectorStorageEntryFor(name: String, index: Int): Array[Byte] = {
    vectorValueClient.getValue(getVectorValueKey(name, index), Array.empty[Byte])
  }

  def updateVectorStorageEntryFor(name: String, index: Int, elem: Array[Byte]) = {
    val size = getVectorStorageSizeFor(name)
    vectorValueClient.put(getVectorValueKey(name, index), elem)
    if (size < index + 1) {
      vectorSizeClient.put(name, IntSerializer.toBytes(index + 1))
    }
  }

  def insertVectorStorageEntriesFor(name: String, elements: List[Array[Byte]]) = {
    var size = getVectorStorageSizeFor(name)
    elements.foreach {
      element =>
        vectorValueClient.put(getVectorValueKey(name, size), element)
        size += 1
    }
    vectorSizeClient.put(name, IntSerializer.toBytes(size))
  }

  def insertVectorStorageEntryFor(name: String, element: Array[Byte]) = {
    insertVectorStorageEntriesFor(name, List(element))
  }


  /**
   * Concat the ownerlenght+owner+key+ of owner so owned data will be colocated
   * Store the length of owner as first byte to work around the rare case
   * where ownerbytes1 + keybytes1 == ownerbytes2 + keybytes2 but ownerbytes1 != ownerbytes2
   */
  def getKey(owner: String, key: Array[Byte]): Array[Byte] = {
    val ownerBytes: Array[Byte] = owner.getBytes("UTF-8")
    val ownerLenghtBytes: Array[Byte] = IntSerializer.toBytes(owner.length)
    val theKey = new Array[Byte](ownerLenghtBytes.length + ownerBytes.length + key.length)
    System.arraycopy(ownerLenghtBytes, 0, theKey, 0, ownerLenghtBytes.length)
    System.arraycopy(ownerBytes, 0, theKey, ownerLenghtBytes.length, ownerBytes.length)
    System.arraycopy(key, 0, theKey, ownerLenghtBytes.length + ownerBytes.length, key.length)
    theKey
  }

  def getVectorValueKey(owner: String, index: Int): Array[Byte] = {
    val indexbytes = IntSerializer.toBytes(index)
    val theIndexKey = new Array[Byte](underscoreBytesUTF8.length + indexbytes.length)
    System.arraycopy(underscoreBytesUTF8, 0, theIndexKey, 0, underscoreBytesUTF8.length)
    System.arraycopy(indexbytes, 0, theIndexKey, underscoreBytesUTF8.length, indexbytes.length)
    getKey(owner, theIndexKey)
  }

  def getIndexFromVectorValueKey(owner: String, key: Array[Byte]): Int = {
    val indexBytes = new Array[Byte](IntSerializer.bytesPerInt)
    System.arraycopy(key, key.length - IntSerializer.bytesPerInt , indexBytes, 0, IntSerializer.bytesPerInt)
    IntSerializer.fromBytes(indexBytes)
  }

  object IntSerializer {
    val bytesPerInt = java.lang.Integer.SIZE / java.lang.Byte.SIZE

    def toBytes(i: Int) = ByteBuffer.wrap(new Array[Byte](bytesPerInt)).putInt(i).array()

    def fromBytes(bytes: Array[Byte]) = ByteBuffer.wrap(bytes).getInt()

    def toString(obj: Int) = obj.toString

    def fromString(str: String) = str.toInt
  }

}