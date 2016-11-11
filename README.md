Description
===========

If you are looking for "Sketching" data structures such as [CountMinSketch](https://en.wikipedia.org/wiki/Count%E2%80%93min_sketch), [BloomFilter](https://en.wikipedia.org/wiki/Bloom_filter), [HyperLogLog](https://en.wikipedia.org/wiki/HyperLogLog) 
or [HyperLogLogPlus](http://static.googleusercontent.com/external_content/untrusted_dlcp/research.google.com/en/us/pubs/archive/40671.pdf), please refer to [StreamLib](https://github.com/addthis/stream-lib) library. 
 
This demo code, on the other hand, mainly focuses on how to build a *Reactive* system on top of those algorithms with Akka-stream. 

Akka Streams is an implementation of [Reactive Streams](http://www.reactive-streams.org/), which is a standard for asynchronous stream processing with non-blocking backpressure.

### Problem Statement
The main motivation of sketching, probabilistic data structures such as CMS, BF, HLL, etc is to "save memory" and is fast to compute compared to brute-force methods.
For many types of application, we would like to answer several questions in *realtime* from an input of both structured and unstructured data-sources pulled from Kafka, FileSystem, etc... such as:
- Cardinality (i.e. counting things): How many times I have seen a particular item in a stream? - CMS
- TopK: what is the most frequent items we have seen from a stream? - CMS + Max Heap / Stream-Summariser
- Set Membership: Did I see a particular item from a stream before? - BF
- Count Distinct: How many distinct element are there in a set? - HLL/HLL+

A remaining question is how to build a platform which is "back-pressure" compliant, distributed and easy to query.


### How to run the demo code (Make sure you run redis locally first)
1. Run master process: sbt 'runMain ReactiveMaster 2551 8080'
2. Run multiple workers process: <br/>
sbt 'runMain ReactiveWorker 6002 ham:sender av:domain av:virus' <br/>
sbt 'runMain ReactiveWorker 6001 ham:ip' <br/>
3. Run a mock client to stream data into master. The mock client will establish 4 different connections and stream data to the server. <br/>
sbt 'runMain ReactiveClient 8080'


### Approach:
The core idea is using [MergeHub](http://doc.akka.io/docs/akka/2.4.12/scala/stream/stream-dynamic.html#Using_the_MergeHub) to merge a dynamic set of producers and SplitterHub to split the load to a dynamic set of consumers.

The SplitterHub implementation has this property:

*The SplitterHub sink[T] method helps to create a \[[Sink]] that receives elements from its upstream producer and split/forward them to a dynamic set of consumers. After the \[[Sink]] returned by this method is materialized, it returns a \[[SplitterHub]] as materialized value. This \[[SplitterHub]].source can be supplied with a predicate/selector function to return a \[[Source]]. The \[[Source]] can be materialized arbitrary many times and each materialization will receive the splitted elements from the original \[[Sink]].*

It can be seen as following:


```
     ___________________________________
	|									|
    |    ___________       ________		|______\ spam:ip consumer/actor
    |   |           |     |        |	|      / 
    |	|   SOURCE  | --->| Sink   |	|
    |   |___________|     |________|	|______\ spam:domain consumer/actor
    |	             			/|\		|      /
    |		                     |      |       
    |	MATERIALIZED SOURCE	 ____| 	    |______\ ham:sender consumer/actor
    |___________________________________|      /

```

Information about \[MergeHub] can be found on the stream-dynamic link on [doc.akka.io](http://doc.akka.io/docs/akka/2.4.12/scala/stream/stream-dynamic.html#Using_the_MergeHub)


```
                             ___________________________________
                            |									|
 spam:ip producer   _______\|    ___________       ________		| 
                           /|   |           |     |        |	| 
                            |	|   SINK    | --->| Source |	|
spam:domain producer_______\|   |___________|     |________|	| 
                           /|	             			 |		|
                            |		                     |      |       
spam:sender producer_______\|   MATERIALIZED SINK	/____| 	    | 
                           /|___________________________________|      

```


You can then connect \[MergeHub] and \[SplitterHub] together to form some sort of bus. Producers and consumers will
connect to \[MergeHub] and \[SplitterHub] correspondingly. It is more or less like "Chat Room" application. 
The differences here is that you are using SplitterHub instead of BroadcastHub.

Consumers and Producers can be either local or remote. Akka-cluster can be used for scaling out.



