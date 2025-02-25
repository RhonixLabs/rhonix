package coop.rchain.rspace.state.instances

import cats.effect.Async
import cats.syntax.all._
import coop.rchain.rspace.hashing.Blake2b256Hash
import coop.rchain.rspace.history.RootsStoreInstances
import coop.rchain.rspace.history.instances.RadixHistory
import coop.rchain.rspace.state.RSpaceExporter
import coop.rchain.shared.ByteVectorOps.RichByteVector
import coop.rchain.shared.syntax._
import coop.rchain.state.TrieNode
import coop.rchain.store.KeyValueStore

import java.nio.ByteBuffer

object RSpaceExporterStore {
  // RSpace exporter constructor / smart constructor "guards" private class
  def apply[F[_]: Async](
      historyStore: KeyValueStore[F],
      valueStore: KeyValueStore[F],
      rootsStore: KeyValueStore[F]
  ): RSpaceExporter[F] = RSpaceExporterImpl(historyStore, valueStore, rootsStore)

  final case object NoRootError extends Exception

  private final case class RSpaceExporterImpl[F[_]: Async](
      sourceHistoryStore: KeyValueStore[F],
      sourceValueStore: KeyValueStore[F],
      sourceRootsStore: KeyValueStore[F]
  ) extends RSpaceExporter[F] {
    import RSpaceExporter._
    import cats.instances.tuple._

    def getItems[Value](
        store: KeyValueStore[F],
        keys: Seq[Blake2b256Hash],
        fromBuffer: ByteBuffer => Value
    ): F[Seq[(Blake2b256Hash, Value)]] =
      for {
        loaded <- store.get(keys.map(_.bytes.toDirectByteBuffer), fromBuffer)
      } yield keys.zip(loaded).filter(_._2.nonEmpty).map(_.map(_.get))

    override def getHistoryItems[Value](
        keys: Seq[Blake2b256Hash],
        fromBuffer: ByteBuffer => Value
    ): F[Seq[(KeyHash, Value)]] = getItems(sourceHistoryStore, keys, fromBuffer)

    override def getDataItems[Value](
        keys: Seq[Blake2b256Hash],
        fromBuffer: ByteBuffer => Value
    ): F[Seq[(KeyHash, Value)]] = getItems(sourceValueStore, keys, fromBuffer)

    override def getNodes(
        startPath: NodePath,
        skip: Int,
        take: Int
    ): F[Seq[TrieNode[Blake2b256Hash]]] = {
      val sourceTrieStore = RadixHistory.createStore(sourceHistoryStore)
      for {
        nodes <- traverseHistory(startPath, skip, take, sourceTrieStore.get1)
      } yield nodes
    }

    override def getRoot: F[Blake2b256Hash] = {
      val rootsStore = RootsStoreInstances.rootsStore(sourceRootsStore)
      for {
        maybeRoot <- rootsStore.currentRoot()
        root      <- maybeRoot.liftTo(NoRootError)
      } yield root
    }
  }
}
